using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using TIREXNET.Core.Update;
using Xunit;

namespace TIREXNET.Tests;

public class UpdateTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "tirex-upd-" + Guid.NewGuid().ToString("N"));

    public UpdateTests() => Directory.CreateDirectory(_root);
    public void Dispose() { try { Directory.Delete(_root, true); } catch (IOException) { } }

    private sealed class FakeVerifier : IUpdateVerifier
    {
        public bool Signed { get; set; }
        public string Signer { get; set; } = "TIREXNET Publisher";
        public bool IsSignedAndTrusted(string path, out string signer) { signer = Signer; return Signed; }
    }

    /// <summary>Serves bytes with Range support. <see cref="CutAfter"/> simulates a connection drop on the first response.</summary>
    private sealed class FakeServer : HttpMessageHandler
    {
        public byte[] Data { get; set; } = Array.Empty<byte>();
        public int CutAfter { get; set; } = -1;
        public bool IgnoreRange { get; set; }
        public List<long?> RangeStarts { get; } = new();
        public string? Json { get; set; }
        public HttpStatusCode Status { get; set; } = HttpStatusCode.OK;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            if (Json != null)
                return Task.FromResult(new HttpResponseMessage(Status) { Content = new StringContent(Json, Encoding.UTF8, "application/json") });
            var from = request.Headers.Range?.Ranges.FirstOrDefault()?.From;
            RangeStarts.Add(from);
            var start = IgnoreRange ? 0 : (int)(from ?? 0);
            var slice = Data[start..];
            Stream body = new MemoryStream(slice);
            if (CutAfter >= 0) { body = new CuttingStream(slice, CutAfter); CutAfter = -1; }
            var resp = new HttpResponseMessage(start > 0 ? HttpStatusCode.PartialContent : HttpStatusCode.OK) { Content = new StreamContent(body) };
            if (start > 0) resp.Content.Headers.ContentRange = new ContentRangeHeaderValue(start, Data.Length - 1, Data.Length);
            return Task.FromResult(resp);
        }
    }

    private sealed class CuttingStream : Stream
    {
        private readonly byte[] _d;
        private readonly int _limit;
        private int _pos;
        public CuttingStream(byte[] d, int limit) { _d = d; _limit = limit; }
        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_pos >= _limit) throw new IOException("connection reset");
            var n = Math.Min(Math.Min(count, _limit - _pos), _d.Length - _pos);
            Array.Copy(_d, _pos, buffer, offset, n);
            _pos += n;
            return n;
        }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    private static byte[] Blob(int n = 300_000)
    {
        var b = new byte[n];
        new Random(42).NextBytes(b);
        return b;
    }

    private static UpdateRelease Release(byte[] data, string version = "1.1.0") => new()
    {
        Version = version,
        Url = "https://updates.example.test/TIREXNET-Setup.exe",
        Sha256 = Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant(),
        Size = data.Length,
        Notes = new() { "Fixes" },
    };

    private UpdateService Service(FakeServer server, string current = "1.0.0", FakeVerifier? v = null, string signer = "") =>
        new(new HttpClient(server), Path.Combine(_root, "updates"), SemVer.Parse(current), v ?? new FakeVerifier(), signer);

    // ------------------------------------------------------------------ versions

    [Theory]
    [InlineData("1.0.0", "1.0.1", -1)]
    [InlineData("1.2.0", "1.10.0", -1)]
    [InlineData("2.0.0", "1.99.99", 1)]
    [InlineData("1.0.0", "1.0.0", 0)]
    [InlineData("1.1.0-beta.1", "1.1.0", -1)]
    [InlineData("1.1.0-beta.2", "1.1.0-beta.10", -1)]
    [InlineData("1.1.0-beta.1", "1.0.9", 1)]
    public void SemVer_Ordering(string a, string b, int expected) =>
        Assert.Equal(expected, Math.Sign(SemVer.Parse(a).CompareTo(SemVer.Parse(b))));

    [Theory]
    [InlineData("")]
    [InlineData("abc")]
    [InlineData("1")]
    [InlineData("1.x.0")]
    public void SemVer_RejectsGarbage(string v) => Assert.False(SemVer.TryParse(v, out _));

    // ------------------------------------------------------------------ manifest + channels

    private static string ManifestJson(UpdateRelease stable, UpdateRelease? beta = null)
    {
        var m = new UpdateManifest();
        m.Channels["stable"] = stable;
        if (beta != null) m.Channels["beta"] = beta;
        return JsonSerializer.Serialize(m);
    }

    [Fact]
    public async Task Check_FindsANewerStableRelease_WithNotesAndSize()
    {
        var data = Blob();
        var server = new FakeServer { Json = ManifestJson(Release(data)) };
        var r = await Service(server).CheckAsync("https://updates.example.test/m.json", "stable");
        Assert.True(r.Available);
        Assert.Equal("1.1.0", r.Latest.ToString());
        Assert.Equal(data.Length, r.Release!.Size);
        Assert.Equal("Fixes", r.Release.Notes[0]);
    }

    [Fact]
    public async Task Check_OldOrEqualVersion_IsNeverOffered_NoDowngrade()
    {
        var data = Blob();
        var older = new FakeServer { Json = ManifestJson(Release(data, "0.9.0")) };
        Assert.False((await Service(older).CheckAsync("https://u.example.test/m.json", "stable")).Available);
        var same = new FakeServer { Json = ManifestJson(Release(data, "1.0.0")) };
        Assert.False((await Service(same).CheckAsync("https://u.example.test/m.json", "stable")).Available);
    }

    [Fact]
    public void Channels_StableUsersNeverSeeBeta_BetaUsersGetTheNewest()
    {
        var data = Blob();
        var m = UpdateService.ParseManifest(ManifestJson(Release(data, "1.1.0"), Release(data, "1.2.0-beta.1")));
        var svc = Service(new FakeServer());
        Assert.Equal("1.1.0", svc.Evaluate(m, "stable").Latest.ToString());
        Assert.Equal("1.2.0-beta.1", svc.Evaluate(m, "beta").Latest.ToString());
        var m2 = UpdateService.ParseManifest(ManifestJson(Release(data, "1.3.0"), Release(data, "1.2.0-beta.1")));
        Assert.Equal("1.3.0", svc.Evaluate(m2, "beta").Latest.ToString());   // a newer stable beats an older beta
    }

    [Fact]
    public void Evaluate_TooOldForADirectUpdate_IsRefusedWithAClearMessage()
    {
        var data = Blob();
        var rel = Release(data, "3.0.0");
        rel.MinVersion = "2.0.0";
        var m = UpdateService.ParseManifest(ManifestJson(rel));
        var ex = Assert.Throws<UpdateException>(() => Service(new FakeServer()).Evaluate(m, "stable"));
        Assert.Equal(UpdateFailure.TooOld, ex.Failure);
    }

    [Theory]
    [InlineData("not json")]
    [InlineData("{}")]
    [InlineData("{\"schema\":2,\"channels\":{\"stable\":{}}}")]
    public void Manifest_Invalid_IsRejected(string json) =>
        Assert.Equal(UpdateFailure.ManifestInvalid, Assert.Throws<UpdateException>(() => UpdateService.ParseManifest(json)).Failure);

    [Fact]
    public void Manifest_HttpDownloadUrl_BadHash_AndZeroSize_AreRejected()
    {
        var data = Blob();
        foreach (var mutate in new Action<UpdateRelease>[]
        {
            r => r.Url = "http://insecure.example.test/x.exe",
            r => r.Sha256 = "abc",
            r => r.Sha256 = new string('z', 64),
            r => r.Size = 0,
            r => r.Version = "latest",
        })
        {
            var rel = Release(data);
            mutate(rel);
            Assert.Equal(UpdateFailure.ManifestInvalid, Assert.Throws<UpdateException>(() => UpdateService.ParseManifest(ManifestJson(rel))).Failure);
        }
    }

    [Fact]
    public async Task Check_NetworkError_IsReportedAsNetwork()
    {
        var server = new FakeServer { Json = "x", Status = HttpStatusCode.InternalServerError };
        var ex = await Assert.ThrowsAsync<UpdateException>(() => Service(server).CheckAsync("https://u.example.test/m.json", "stable"));
        Assert.Equal(UpdateFailure.Network, ex.Failure);
        await Assert.ThrowsAsync<UpdateException>(() => Service(server).CheckAsync("http://u.example.test/m.json", "stable"));
    }

    // ------------------------------------------------------------------ download

    [Fact]
    public async Task Download_Complete_VerifiesAndReturnsTheInstaller()
    {
        var data = Blob();
        var svc = Service(new FakeServer { Data = data });
        var path = await svc.DownloadAsync(Release(data));
        Assert.Equal(data, await File.ReadAllBytesAsync(path));
        Assert.False(File.Exists(svc.PartPath(Release(data))));
    }

    [Fact]
    public async Task Download_Interrupted_KeepsThePartFile_AndResumesWithARangeRequest()
    {
        var data = Blob();
        var server = new FakeServer { Data = data, CutAfter = 120_000 };
        var svc = Service(server);
        var rel = Release(data);
        var ex = await Assert.ThrowsAsync<UpdateException>(() => svc.DownloadAsync(rel));
        Assert.Equal(UpdateFailure.Network, ex.Failure);
        var part = svc.PartPath(rel);
        Assert.True(File.Exists(part));
        var kept = new FileInfo(part).Length;
        Assert.InRange(kept, 1, 120_000);

        var path = await svc.DownloadAsync(rel);
        Assert.Equal((long?)kept, server.RangeStarts.Last());      // second request asked to continue where the first stopped
        Assert.Equal(data, await File.ReadAllBytesAsync(path));
    }

    [Fact]
    public async Task Download_ServerIgnoringRange_RestartsCleanly()
    {
        var data = Blob();
        var server = new FakeServer { Data = data, CutAfter = 50_000, IgnoreRange = true };
        var svc = Service(server);
        var rel = Release(data);
        await Assert.ThrowsAsync<UpdateException>(() => svc.DownloadAsync(rel));
        var path = await svc.DownloadAsync(rel);
        Assert.Equal(data, await File.ReadAllBytesAsync(path));
    }

    [Fact]
    public async Task Download_CorruptFile_IsRejectedAndDeleted()
    {
        var data = Blob();
        var bad = (byte[])data.Clone();
        bad[1000] ^= 0xFF;
        var svc = Service(new FakeServer { Data = bad });
        var rel = Release(data);
        var ex = await Assert.ThrowsAsync<UpdateException>(() => svc.DownloadAsync(rel));
        Assert.Equal(UpdateFailure.HashMismatch, ex.Failure);
        Assert.False(File.Exists(svc.PartPath(rel)));
        Assert.False(File.Exists(svc.FinalPath(rel)));
    }

    [Fact]
    public async Task Download_WrongSize_IsRejected()
    {
        var data = Blob();
        var rel = Release(data);
        rel.Size = data.Length + 10;                    // server delivers fewer bytes than announced
        var svc = Service(new FakeServer { Data = data });
        var ex = await Assert.ThrowsAsync<UpdateException>(() => svc.DownloadAsync(rel));
        Assert.Equal(UpdateFailure.SizeMismatch, ex.Failure);
    }

    [Fact]
    public async Task Download_LargerThanAnnounced_IsAbortedAndDeleted()
    {
        var data = Blob();
        var rel = Release(data);
        rel.Size = data.Length - 1000;
        var svc = Service(new FakeServer { Data = data });
        var ex = await Assert.ThrowsAsync<UpdateException>(() => svc.DownloadAsync(rel));
        Assert.Equal(UpdateFailure.SizeMismatch, ex.Failure);
        Assert.False(File.Exists(svc.PartPath(rel)));
    }

    [Fact]
    public async Task Download_AFileTamperedAfterDownload_IsRedownloaded()
    {
        var data = Blob();
        var server = new FakeServer { Data = data };
        var svc = Service(server);
        var rel = Release(data);
        var path = await svc.DownloadAsync(rel);
        await File.WriteAllBytesAsync(path, new byte[data.Length]);     // someone swaps the file
        var again = await svc.DownloadAsync(rel);
        Assert.Equal(data, await File.ReadAllBytesAsync(again));
    }

    // ------------------------------------------------------------------ signatures

    [Fact]
    public async Task SignedBuild_RefusesAnUnsignedUpdate()
    {
        var data = Blob();
        var svc = Service(new FakeServer { Data = data }, v: new FakeVerifier { Signed = false }, signer: "TIREXNET Publisher");
        var ex = await Assert.ThrowsAsync<UpdateException>(() => svc.DownloadAsync(Release(data)));
        Assert.Equal(UpdateFailure.SignatureInvalid, ex.Failure);
    }

    [Fact]
    public async Task SignedBuild_RefusesADifferentPublisher()
    {
        var data = Blob();
        var svc = Service(new FakeServer { Data = data }, v: new FakeVerifier { Signed = true, Signer = "Someone Else" }, signer: "TIREXNET Publisher");
        var ex = await Assert.ThrowsAsync<UpdateException>(() => svc.DownloadAsync(Release(data)));
        Assert.Equal(UpdateFailure.SignerChanged, ex.Failure);
    }

    [Fact]
    public async Task SignedBuild_AcceptsTheSamePublisher()
    {
        var data = Blob();
        var svc = Service(new FakeServer { Data = data }, v: new FakeVerifier { Signed = true }, signer: "TIREXNET Publisher");
        Assert.True(File.Exists(await svc.DownloadAsync(Release(data))));
    }

    [Fact]
    public async Task ManifestCanRequireASignature_EvenForAnUnsignedBuild()
    {
        var data = Blob();
        var rel = Release(data);
        rel.RequireSignature = true;
        var svc = Service(new FakeServer { Data = data }, v: new FakeVerifier { Signed = false });
        var ex = await Assert.ThrowsAsync<UpdateException>(() => svc.DownloadAsync(rel));
        Assert.Equal(UpdateFailure.SignatureInvalid, ex.Failure);
    }

    // ------------------------------------------------------------------ install state + rollback

    private (string app, string user) MakeApp()
    {
        var app = Path.Combine(_root, "app");
        Directory.CreateDirectory(Path.Combine(app, "engines"));
        File.WriteAllText(Path.Combine(app, "TIREXNET.exe"), "old-exe");
        File.WriteAllText(Path.Combine(app, "engines", "x.dll"), "old-dll");
        var user = Path.Combine(_root, "userdata");
        Directory.CreateDirectory(user);
        File.WriteAllText(Path.Combine(user, "profiles.dat"), "PROFILES");
        return (app, user);
    }

    [Fact]
    public void Rollback_RestoresTheOldFiles_AndLeavesUserDataAlone()
    {
        var (app, user) = MakeApp();
        var svc = Service(new FakeServer());
        svc.PrepareInstall(app, SemVer.Parse("1.1.0"));
        // the failed installer half-replaced the application
        File.WriteAllText(Path.Combine(app, "TIREXNET.exe"), "broken-new");
        File.Delete(Path.Combine(app, "engines", "x.dll"));

        var newer = Service(new FakeServer(), current: "1.1.0");
        Assert.Equal(UpdateService.PendingAction.None, newer.Decide(newer.ReadPending()));      // first starts: not yet judged
        newer.Rollback(app);

        Assert.Equal("old-exe", File.ReadAllText(Path.Combine(app, "TIREXNET.exe")));
        Assert.Equal("old-dll", File.ReadAllText(Path.Combine(app, "engines", "x.dll")));
        Assert.Equal("PROFILES", File.ReadAllText(Path.Combine(user, "profiles.dat")));
        Assert.Null(newer.ReadPending());
    }

    [Fact]
    public void Rollback_WorksWhenTheRunningExecutableIsLocked()
    {
        var (app, _) = MakeApp();
        var svc = Service(new FakeServer());
        svc.PrepareInstall(app, SemVer.Parse("1.1.0"));
        var exe = Path.Combine(app, "TIREXNET.exe");
        File.WriteAllText(exe, "broken-new");
        using (new FileStream(exe, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))   // like a running process
        {
            svc.Rollback(app);
        }
        Assert.Equal("old-exe", File.ReadAllText(exe));
        UpdateService.CleanupAside(app);
        Assert.Empty(Directory.GetFiles(app, "*.old-*"));
    }

    [Fact]
    public void FailedInstall_WhereTheVersionIsNeitherOldNorNew_TriggersRollback()
    {
        var (app, _) = MakeApp();
        Service(new FakeServer()).PrepareInstall(app, SemVer.Parse("1.1.0"));
        var odd = Service(new FakeServer(), current: "1.0.5");
        Assert.Equal(UpdateService.PendingAction.Rollback, odd.Decide(odd.ReadPending()));
    }

    [Fact]
    public void InstallerThatNeverRan_LeavesTheOldVersionUntouched()
    {
        var (app, _) = MakeApp();
        var svc = Service(new FakeServer());
        svc.PrepareInstall(app, SemVer.Parse("1.1.0"));
        Assert.Equal(UpdateService.PendingAction.None, svc.OnStartup());
    }

    [Fact]
    public void NewVersionThatStartsAndStaysUp_IsConfirmed_AndTheBackupIsRemoved()
    {
        var (app, _) = MakeApp();
        var old = Service(new FakeServer());
        var pending = old.PrepareInstall(app, SemVer.Parse("1.1.0"));
        var now = Service(new FakeServer(), current: "1.1.0");
        Assert.Equal(UpdateService.PendingAction.None, now.OnStartup());
        now.MarkHealthy();
        Assert.Equal(UpdateService.PendingAction.Confirmed, now.OnStartup());
        Assert.Null(now.ReadPending());
        Assert.False(Directory.Exists(pending.BackupDir));
    }

    [Fact]
    public void NewVersionThatNeverBecomesHealthy_IsRolledBackAfterThreeStarts()
    {
        var (app, _) = MakeApp();
        Service(new FakeServer()).PrepareInstall(app, SemVer.Parse("1.1.0"));
        var now = Service(new FakeServer(), current: "1.1.0");
        Assert.Equal(UpdateService.PendingAction.None, now.OnStartup());
        Assert.Equal(UpdateService.PendingAction.None, now.OnStartup());
        Assert.Equal(UpdateService.PendingAction.None, now.OnStartup());
        Assert.Equal(UpdateService.PendingAction.Rollback, now.OnStartup());
    }

    [Fact]
    public void Rollback_WithoutABackup_FailsClearly()
    {
        var svc = Service(new FakeServer());
        Assert.Equal(UpdateFailure.InstallFailed, Assert.Throws<UpdateException>(() => svc.Rollback(_root)).Failure);
    }

    [Fact]
    public void InstallerArguments_AreSilent_AndRestartTheApp()
    {
        Assert.Contains("/VERYSILENT", UpdateService.InstallerArguments);
        Assert.Contains("/CLOSEAPPLICATIONS", UpdateService.InstallerArguments);
        Assert.Contains("/RESTARTAPPLICATIONS", UpdateService.InstallerArguments);
    }
}

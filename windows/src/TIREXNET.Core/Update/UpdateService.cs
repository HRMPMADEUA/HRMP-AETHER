using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;

namespace TIREXNET.Core.Update;

public sealed class UpdateService
{
    public const long MaxInstallerBytes = 600L * 1024 * 1024;
    private static readonly JsonSerializerOptions JsonOpts = new() { PropertyNameCaseInsensitive = true };

    private readonly HttpClient _http;
    private readonly string _dir;
    private readonly IUpdateVerifier _verifier;

    public SemVer Current { get; }
    /// <summary>Signer subject of the running build, or empty when it is unsigned (development / CI build).</summary>
    public string RunningSigner { get; }

    public UpdateService(HttpClient http, string updatesDir, SemVer current, IUpdateVerifier verifier, string runningSigner = "")
    {
        _http = http;
        _dir = updatesDir;
        Current = current;
        _verifier = verifier;
        RunningSigner = runningSigner;
        Directory.CreateDirectory(_dir);
    }

    // ------------------------------------------------------------------ manifest

    public static bool IsHttps(string? url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == Uri.UriSchemeHttps;

    public static UpdateManifest ParseManifest(string json)
    {
        UpdateManifest? m;
        try { m = JsonSerializer.Deserialize<UpdateManifest>(json, JsonOpts); }
        catch (JsonException ex) { throw new UpdateException(UpdateFailure.ManifestInvalid, "The update manifest is not valid JSON.", ex); }
        if (m == null || m.Schema != 1 || m.Channels.Count == 0)
            throw new UpdateException(UpdateFailure.ManifestInvalid, "The update manifest has an unsupported format.");
        foreach (var (name, r) in m.Channels) ValidateRelease(name, r);
        return m;
    }

    private static void ValidateRelease(string channel, UpdateRelease r)
    {
        if (!SemVer.TryParse(r.Version, out _)) throw new UpdateException(UpdateFailure.ManifestInvalid, $"Channel {channel}: invalid version.");
        if (!IsHttps(r.Url)) throw new UpdateException(UpdateFailure.ManifestInvalid, $"Channel {channel}: the download URL must be https.");
        if (r.Sha256.Length != 64 || !r.Sha256.All(Uri.IsHexDigit)) throw new UpdateException(UpdateFailure.ManifestInvalid, $"Channel {channel}: the SHA-256 value is missing or malformed.");
        if (r.Size <= 0 || r.Size > MaxInstallerBytes) throw new UpdateException(UpdateFailure.ManifestInvalid, $"Channel {channel}: invalid download size.");
        if (r.MinVersion.Length > 0 && !SemVer.TryParse(r.MinVersion, out _)) throw new UpdateException(UpdateFailure.ManifestInvalid, $"Channel {channel}: invalid minVersion.");
    }

    /// <summary>Stable users only see the stable channel; Beta users get the newest of stable and beta.</summary>
    public UpdateCheckResult Evaluate(UpdateManifest manifest, string channel)
    {
        var want = channel.Equals("beta", StringComparison.OrdinalIgnoreCase) ? new[] { "stable", "beta" } : new[] { "stable" };
        UpdateRelease? best = null;
        string bestChannel = "stable";
        SemVer bestVer = default;
        foreach (var c in want)
        {
            if (!manifest.Channels.TryGetValue(c, out var r)) continue;
            var v = SemVer.Parse(r.Version);
            if (best == null || v > bestVer) { best = r; bestVer = v; bestChannel = c; }
        }
        if (best == null) throw new UpdateException(UpdateFailure.NoChannel, $"The manifest has no \"{channel}\" release.");
        var available = bestVer > Current;     // equal or older is never offered: no downgrade
        if (available && best.MinVersion.Length > 0 && Current < SemVer.Parse(best.MinVersion))
            throw new UpdateException(UpdateFailure.TooOld, $"Version {Current} is too old to update directly; install {best.MinVersion} first.");
        return new UpdateCheckResult { Available = available, Current = Current, Latest = bestVer, Release = best, Channel = bestChannel };
    }

    public async Task<UpdateCheckResult> CheckAsync(string manifestUrl, string channel, CancellationToken ct = default)
    {
        if (!IsHttps(manifestUrl)) throw new UpdateException(UpdateFailure.ManifestInvalid, "The update manifest URL must be https.");
        string body;
        try
        {
            using var resp = await _http.GetAsync(manifestUrl, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false);
            resp.EnsureSuccessStatusCode();
            if (resp.Content.Headers.ContentLength is > 1_000_000) throw new UpdateException(UpdateFailure.ManifestInvalid, "The update manifest is too large.");
            body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
        {
            throw new UpdateException(UpdateFailure.Network, "Could not reach the update server: " + ex.Message, ex);
        }
        if (body.Length > 1_000_000) throw new UpdateException(UpdateFailure.ManifestInvalid, "The update manifest is too large.");
        return Evaluate(ParseManifest(body), channel);
    }

    // ------------------------------------------------------------------ download (resumable)

    public string PartPath(UpdateRelease r) => Path.Combine(_dir, $"TIREXNET-{SemVer.Parse(r.Version)}-{r.Sha256[..12]}.part");
    public string FinalPath(UpdateRelease r) => Path.Combine(_dir, $"TIREXNET-{SemVer.Parse(r.Version)}-{r.Sha256[..12]}.exe");

    /// <summary>Downloads (or resumes) the installer, then verifies size and SHA-256. A corrupt file is deleted, never kept.</summary>
    public async Task<string> DownloadAsync(UpdateRelease r, IProgress<(long done, long total)>? progress = null, CancellationToken ct = default)
    {
        ValidateRelease("download", r);
        var final = FinalPath(r);
        if (File.Exists(final))
        {
            try { VerifyFile(final, r); return final; }
            catch (UpdateException) { TryDelete(final); }
        }
        var part = PartPath(r);
        long have = File.Exists(part) ? new FileInfo(part).Length : 0;
        if (have > r.Size) { TryDelete(part); have = 0; }

        if (have < r.Size)
        {
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Get, r.Url);
                if (have > 0) req.Headers.Range = new RangeHeaderValue(have, null);
                using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
                if (resp.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable) { TryDelete(part); have = 0; throw new UpdateException(UpdateFailure.Network, "The server rejected the resume request; the download will restart."); }
                resp.EnsureSuccessStatusCode();
                var resumed = resp.StatusCode == HttpStatusCode.PartialContent;
                if (!resumed) have = 0;           // server ignored Range: start over
                await using var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
                await using var dst = new FileStream(part, resumed ? FileMode.Append : FileMode.Create, FileAccess.Write, FileShare.None);
                var buf = new byte[81920];
                int n;
                var done = have;
                while ((n = await src.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
                {
                    done += n;
                    if (done > r.Size) { dst.Close(); TryDelete(part); throw new UpdateException(UpdateFailure.SizeMismatch, "The download is larger than announced."); }
                    await dst.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                    progress?.Report((done, r.Size));
                }
            }
            catch (Exception ex) when (ex is HttpRequestException or IOException)
            {
                // The .part file is kept so the next attempt resumes.
                throw new UpdateException(UpdateFailure.Network, "The download was interrupted: " + ex.Message + " It will resume next time.", ex);
            }
        }

        try { VerifyFile(part, r); }
        catch (UpdateException) { TryDelete(part); throw; }
        File.Move(part, final, true);
        return final;
    }

    // ------------------------------------------------------------------ verification

    /// <summary>Size, SHA-256 and (when applicable) Authenticode. Throws before anything can be installed.</summary>
    public void VerifyFile(string path, UpdateRelease r)
    {
        var info = new FileInfo(path);
        if (!info.Exists) throw new UpdateException(UpdateFailure.Corrupt, "The downloaded update is missing.");
        if (info.Length != r.Size) throw new UpdateException(UpdateFailure.SizeMismatch, $"The update has the wrong size ({info.Length} of {r.Size} bytes).");
        string hash;
        using (var fs = File.OpenRead(path)) hash = Convert.ToHexString(SHA256.HashData(fs));
        if (!hash.Equals(r.Sha256, StringComparison.OrdinalIgnoreCase))
            throw new UpdateException(UpdateFailure.HashMismatch, "The update failed its SHA-256 check (damaged or tampered). It was discarded.");

        var mustBeSigned = r.RequireSignature || RunningSigner.Length > 0;
        var signed = _verifier.IsSignedAndTrusted(path, out var signer);
        if (mustBeSigned && !signed)
            throw new UpdateException(UpdateFailure.SignatureInvalid, "The update has no valid digital signature and was refused.");
        if (signed && RunningSigner.Length > 0 && !string.Equals(signer, RunningSigner, StringComparison.OrdinalIgnoreCase))
            throw new UpdateException(UpdateFailure.SignerChanged, "The update is signed by a different publisher than the installed version and was refused.");
    }

    // ------------------------------------------------------------------ install + rollback

    public sealed class PendingUpdate
    {
        public string From { get; set; } = "";
        public string To { get; set; } = "";
        public string BackupDir { get; set; } = "";
        public int Starts { get; set; }
        public bool Healthy { get; set; }
    }

    public enum PendingAction { None, Confirmed, Rollback }

    private string PendingPath => Path.Combine(_dir, "pending-update.json");

    public PendingUpdate? ReadPending()
    {
        try { return File.Exists(PendingPath) ? JsonSerializer.Deserialize<PendingUpdate>(File.ReadAllText(PendingPath)) : null; }
        catch (Exception ex) when (ex is IOException or JsonException) { return null; }
    }

    private void WritePending(PendingUpdate p) => File.WriteAllText(PendingPath, JsonSerializer.Serialize(p));

    /// <summary>Copies the installed application next to the updates so a failed update can be undone. User data lives outside the app folder and is never touched.</summary>
    public PendingUpdate PrepareInstall(string appDir, SemVer target)
    {
        var backup = Path.Combine(_dir, "backup-" + Current);
        if (Directory.Exists(backup)) Directory.Delete(backup, true);
        CopyTree(appDir, backup);
        var p = new PendingUpdate { From = Current.ToString(), To = target.ToString(), BackupDir = backup };
        WritePending(p);
        return p;
    }

    /// <summary>Called at every start. Decides whether the previous update succeeded, is still unconfirmed, or must be rolled back.</summary>
    public PendingAction Decide(PendingUpdate? p)
    {
        if (p == null) return PendingAction.None;
        if (SemVer.TryParse(p.To, out var to) && Current == to)
            return p.Healthy ? PendingAction.Confirmed : p.Starts >= 3 ? PendingAction.Rollback : PendingAction.None;
        if (SemVer.TryParse(p.From, out var from) && Current == from) return PendingAction.None;   // installer never ran / aborted before replacing files
        return PendingAction.Rollback;                                                              // neither version: a partial install
    }

    public PendingAction OnStartup()
    {
        var p = ReadPending();
        var action = Decide(p);
        if (p == null) return action;
        if (action == PendingAction.None && SemVer.TryParse(p.To, out var to) && Current == to) { p.Starts++; WritePending(p); }
        if (action == PendingAction.Confirmed) { DiscardPending(p); }
        return action;
    }

    public void MarkHealthy()
    {
        var p = ReadPending();
        if (p == null || !SemVer.TryParse(p.To, out var to) || Current != to) return;
        p.Healthy = true;
        WritePending(p);
    }

    private void DiscardPending(PendingUpdate p)
    {
        TryDelete(PendingPath);
        try { if (Directory.Exists(p.BackupDir)) Directory.Delete(p.BackupDir, true); } catch (IOException) { }
    }

    /// <summary>Restores the pre-update files. Files that are in use (the running exe) are renamed aside first, which Windows allows.</summary>
    public void Rollback(string appDir)
    {
        var p = ReadPending() ?? throw new UpdateException(UpdateFailure.InstallFailed, "There is no update to roll back.");
        if (!Directory.Exists(p.BackupDir)) throw new UpdateException(UpdateFailure.InstallFailed, "The backup of the previous version is missing.");
        RestoreTree(p.BackupDir, appDir);
        TryDelete(PendingPath);
    }

    public static void RestoreTree(string backup, string target)
    {
        foreach (var file in Directory.EnumerateFiles(backup, "*", SearchOption.AllDirectories))
        {
            var rel = Path.GetRelativePath(backup, file);
            var dest = Path.Combine(target, rel);
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            try { File.Copy(file, dest, true); }
            catch (IOException)
            {
                var aside = dest + ".old-" + Guid.NewGuid().ToString("N")[..8];
                File.Move(dest, aside);
                File.Copy(file, dest, true);
            }
        }
    }

    public static void CleanupAside(string appDir)
    {
        foreach (var f in Directory.EnumerateFiles(appDir, "*.old-*", SearchOption.AllDirectories)) TryDelete(f);
    }

    private static void CopyTree(string from, string to)
    {
        Directory.CreateDirectory(to);
        foreach (var f in Directory.EnumerateFiles(from, "*", SearchOption.AllDirectories))
        {
            var rel = Path.GetRelativePath(from, f);
            if (rel.Contains(".old-")) continue;
            var dest = Path.Combine(to, rel);
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            File.Copy(f, dest, true);
        }
    }

    /// <summary>Arguments for the Inno Setup installer: silent, closes and restarts the app, keeps %APPDATA% data.</summary>
    public static string InstallerArguments => "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /RESTARTAPPLICATIONS";

    private static void TryDelete(string path) { try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { } }
}

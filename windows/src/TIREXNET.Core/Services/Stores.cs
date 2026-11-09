using System.Text.Json;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Services;

public sealed class SettingsStore
{
    private readonly string _path;
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public AppSettings Current { get; private set; } = new();

    public SettingsStore(string dir)
    {
        _path = Path.Combine(dir, "settings.json");
        Load();
    }

    private void Load()
    {
        try
        {
            if (File.Exists(_path))
                Current = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(_path)) ?? new AppSettings();
        }
        catch (Exception ex) when (ex is IOException or JsonException)
        {
            Log.Warn("Settings could not be read; defaults are used.");
            Current = new AppSettings();
        }
    }

    public void Save(AppSettings settings)
    {
        Current = settings;
        var tmp = _path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(settings, Json));
        File.Move(tmp, _path, true);
    }

    public void Save() => Save(Current);
}

/// <summary>Profiles are stored as one DPAPI-encrypted blob. Nothing sensitive is ever written in plain text.</summary>
public sealed class ProfileStore
{
    private sealed class Envelope
    {
        public int Version { get; set; } = 1;
        public string SelectedId { get; set; } = "";
        public List<Profile> Profiles { get; set; } = new();
    }

    private readonly string _path;
    private readonly object _gate = new();
    private Envelope _data = new();

    public event Action? Changed;
    public bool LoadFailed { get; private set; }

    public ProfileStore(string dir)
    {
        _path = Path.Combine(dir, "profiles.dat");
        Load();
    }

    private void Load()
    {
        var text = Secure.ReadEncrypted(_path, out var corrupt);
        if (corrupt)
        {
            LoadFailed = true;
            Log.Error("The profile database could not be decrypted (different Windows user?). It was kept as profiles.dat.unreadable.");
        }
        if (text == null) return;
        try { _data = JsonSerializer.Deserialize<Envelope>(text) ?? new Envelope(); }
        catch (JsonException) { LoadFailed = true; _data = new Envelope(); }
    }

    private void Persist()
    {
        Secure.WriteEncrypted(_path, JsonSerializer.Serialize(_data));
    }

    public IReadOnlyList<Profile> All()
    {
        lock (_gate) return _data.Profiles.ToList();
    }

    public Profile? Get(string id)
    {
        lock (_gate) return _data.Profiles.FirstOrDefault(p => p.Id == id);
    }

    public string SelectedId
    {
        get { lock (_gate) return _data.SelectedId; }
        set
        {
            lock (_gate) { _data.SelectedId = value; Persist(); }
            Changed?.Invoke();
        }
    }

    public Profile? Selected
    {
        get { lock (_gate) return _data.Profiles.FirstOrDefault(p => p.Id == _data.SelectedId); }
    }

    public void Add(Profile profile)
    {
        lock (_gate)
        {
            _data.Profiles.Add(profile);
            if (string.IsNullOrEmpty(_data.SelectedId)) _data.SelectedId = profile.Id;
            Persist();
        }
        Changed?.Invoke();
    }

    public void Update(Profile profile)
    {
        lock (_gate)
        {
            var i = _data.Profiles.FindIndex(p => p.Id == profile.Id);
            if (i < 0) return;
            _data.Profiles[i] = profile;
            Persist();
        }
        Changed?.Invoke();
    }

    public bool Rename(string id, string name)
    {
        lock (_gate)
        {
            var p = _data.Profiles.FirstOrDefault(x => x.Id == id);
            if (p == null || string.IsNullOrWhiteSpace(name)) return false;
            p.Name = name.Trim();
            Persist();
        }
        Changed?.Invoke();
        return true;
    }

    public void Remove(string id)
    {
        lock (_gate)
        {
            _data.Profiles.RemoveAll(p => p.Id == id);
            if (_data.SelectedId == id) _data.SelectedId = _data.Profiles.FirstOrDefault()?.Id ?? "";
            Persist();
        }
        Changed?.Invoke();
    }

    /// <summary>Replaces every profile that came from <paramref name="source"/> with a fresh set (account sync).</summary>
    public void ReplaceSynced(string source, IEnumerable<Profile> fresh)
    {
        lock (_gate)
        {
            _data.Profiles.RemoveAll(p => p.Source == source);
            _data.Profiles.AddRange(fresh);
            if (!_data.Profiles.Any(p => p.Id == _data.SelectedId)) _data.SelectedId = _data.Profiles.FirstOrDefault()?.Id ?? "";
            Persist();
        }
        Changed?.Invoke();
    }

    public void RemoveBySource(string source)
    {
        lock (_gate)
        {
            _data.Profiles.RemoveAll(p => p.Source == source);
            if (!_data.Profiles.Any(p => p.Id == _data.SelectedId)) _data.SelectedId = _data.Profiles.FirstOrDefault()?.Id ?? "";
            Persist();
        }
        Changed?.Invoke();
    }

    /// <summary>Imports parsed profiles, skipping exact duplicates. Returns the number added.</summary>
    public int Import(IEnumerable<Profile> profiles)
    {
        var added = 0;
        lock (_gate)
        {
            foreach (var p in profiles)
            {
                if (_data.Profiles.Any(x => x.Raw == p.Raw && x.Source == p.Source)) continue;
                _data.Profiles.Add(p);
                added++;
            }
            if (string.IsNullOrEmpty(_data.SelectedId) && _data.Profiles.Count > 0) _data.SelectedId = _data.Profiles[0].Id;
            if (added > 0) Persist();
        }
        if (added > 0) Changed?.Invoke();
        return added;
    }

    /// <summary>Writes the profile in its native text format. The caller warns the user that the file is not encrypted.</summary>
    public static string ExportText(Profile p) => p.Raw;

    public static string ExportFileName(Profile p)
    {
        var safe = new string(p.Name.Select(c => char.IsLetterOrDigit(c) || c is '-' or '_' or ' ' ? c : '_').ToArray()).Trim();
        if (safe.Length == 0) safe = "profile";
        var ext = p.Engine == EngineKind.WireGuard ? ".conf" : (p.Protocol == "xray-json" ? ".json" : ".txt");
        return safe + ext;
    }
}

public sealed class PanelSession
{
    public string Token { get; set; } = "";
    public string Username { get; set; } = "";
}

/// <summary>Panel session tokens (PasarGuard / AliPanel), DPAPI protected.</summary>
public sealed class SessionStore
{
    private readonly string _path;
    private Dictionary<string, PanelSession> _sessions = new();

    public SessionStore(string dir)
    {
        _path = Path.Combine(dir, "sessions.dat");
        var text = Secure.ReadEncrypted(_path, out _);
        if (text != null)
        {
            try { _sessions = JsonSerializer.Deserialize<Dictionary<string, PanelSession>>(text) ?? new(); }
            catch (JsonException) { _sessions = new(); }
        }
        foreach (var s in _sessions.Values) Redactor.AddSecret(s.Token);
    }

    public PanelSession? Get(string panel) => _sessions.TryGetValue(panel, out var s) ? s : null;

    public void Set(string panel, string token, string username)
    {
        _sessions[panel] = new PanelSession { Token = token, Username = username };
        Redactor.AddSecret(token);
        Save();
    }

    public void Remove(string panel)
    {
        if (_sessions.Remove(panel)) Save();
    }

    private void Save() => Secure.WriteEncrypted(_path, JsonSerializer.Serialize(_sessions));
}

using System;
using TIREXNET.Core;
using TIREXNET.Core.Engines;
using TIREXNET.Core.Services;

namespace TIREXNET.UI
{
    /// <summary>Application-wide singletons (settings, encrypted stores, API client, connection manager).</summary>
    internal static class AppServices
    {
        public static SettingsStore Settings { get; private set; } = null!;
        public static ProfileStore Profiles { get; private set; } = null!;
        public static SessionStore Sessions { get; private set; } = null!;
        public static ApiClient Api { get; private set; } = null!;
        public static ConnectionManager Manager { get; private set; } = null!;

        public static void Initialize()
        {
            var dir = AppPaths.DataDir;
            Settings = new SettingsStore(dir);
            Profiles = new ProfileStore(dir);
            Sessions = new SessionStore(dir);
            Api = new ApiClient();
            Manager = new ConnectionManager();
        }
    }
}

using System;
using System.Linq;
using System.Threading;
using System.Windows;
using TIREXNET.Core;
using TIREXNET.Core.Engines;

namespace TIREXNET.UI
{
    public partial class TirexApp : System.Windows.Application
    {
        private Mutex? _single;
        public static bool AutoConnect { get; private set; }

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            var args = e.Args;

            // Used by the uninstaller: undo any leftover routes / services, then exit without showing UI.
            if (args.Contains("--cleanup", StringComparer.OrdinalIgnoreCase))
            {
                try { Recovery.CleanupAsync().GetAwaiter().GetResult(); }
                catch (Exception ex) { Log.Error("Cleanup failed: " + ex.Message); }
                Shutdown(0);
                return;
            }

            var elevated = args.Contains("--elevated", StringComparer.OrdinalIgnoreCase);
            AutoConnect = args.Contains("--autoconnect", StringComparer.OrdinalIgnoreCase);
            _single = new Mutex(false, "Local\\TIREXNET.Windows.SingleInstance");
            bool owned;
            try { owned = _single.WaitOne(elevated ? 8000 : 0); }
            catch (AbandonedMutexException) { owned = true; }
            if (!owned)
            {
                System.Windows.MessageBox.Show("TIREXNET is already running. Look for it in the system tray.", "TIREXNET", MessageBoxButton.OK, MessageBoxImage.Information);
                Shutdown(0);
                return;
            }

            DispatcherUnhandledException += (_, ev) =>
            {
                Log.Error("Unhandled error: " + ev.Exception);
                System.Windows.MessageBox.Show("Something went wrong: " + ev.Exception.Message + "\n\nDetails were written to the log.", "TIREXNET", MessageBoxButton.OK, MessageBoxImage.Error);
                ev.Handled = true;
            };
            AppDomain.CurrentDomain.UnhandledException += (_, ev) =>
            {
                Log.Error("Fatal error: " + ev.ExceptionObject);
                try { AppServices.Manager?.ShutdownBlocking(); } catch { /* best effort */ }
            };
            SessionEnding += (_, _) => { try { AppServices.Manager?.ShutdownBlocking(); } catch { /* best effort */ } };

            AppServices.Initialize();
            var window = new MainWindow();
            MainWindow = window;
            var minimized = AppServices.Settings.Current.StartMinimized && !AutoConnect;
            if (!minimized) window.Show();
            else window.PrepareHidden();
        }

        protected override void OnExit(ExitEventArgs e)
        {
            try { AppServices.Manager?.ShutdownBlocking(); } catch { /* best effort */ }
            try { _single?.ReleaseMutex(); } catch { /* not owned */ }
            base.OnExit(e);
        }
    }
}

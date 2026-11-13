using System;
using System.Linq;
using System.Threading;
using System.Windows;
using TIREXNET.Core;
using TIREXNET.Core.Engines;
using TIREXNET.Core.Services;

namespace TIREXNET.UI
{
    public partial class TirexApp : System.Windows.Application
    {
        private Mutex? _single;
        private ActivationServer? _activation;
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
            var startupLink = UriActivation.FindLink(args);
            if (!owned)
            {
                // Hand the clicked tirexnet:// link (or a plain "show window" request) to the running instance; never start a second one.
                if (!ActivationServer.TrySend(UriActivation.DefaultPipeName(), startupLink))
                    System.Windows.MessageBox.Show("TIREXNET is already running. Look for it in the system tray.", "TIREXNET", MessageBoxButton.OK, MessageBoxImage.Information);
                Shutdown(0);
                return;
            }

            DispatcherUnhandledException += (_, ev) =>
            {
                Log.Error("Unhandled error: " + ev.Exception);
                System.Windows.MessageBox.Show("Something went wrong: " + Redactor.Redact(ev.Exception.Message) + "\n\nDetails were written to the log.", "TIREXNET", MessageBoxButton.OK, MessageBoxImage.Error);
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
            if (!minimized || startupLink != null) window.Show();
            else window.PrepareHidden();

            _activation = new ActivationServer(UriActivation.DefaultPipeName(), link =>
                Dispatcher.BeginInvoke(new Action(() => window.HandleActivation(link))));
            _activation.Start();
            window.StartUpdateChecks();
            if (startupLink != null) Dispatcher.BeginInvoke(new Action(() => window.HandleActivation(startupLink)));
        }

        protected override void OnExit(ExitEventArgs e)
        {
            try { AppServices.Manager?.ShutdownBlocking(); } catch { /* best effort */ }
            try { _activation?.Dispose(); } catch { /* best effort */ }
            try { _single?.ReleaseMutex(); } catch { /* not owned */ }
            base.OnExit(e);
        }
    }
}

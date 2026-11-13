using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using TIREXNET.Core;
using TIREXNET.Core.Engines;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;
using TIREXNET.Core.Update;

namespace TIREXNET.UI
{
    public partial class MainWindow
    {
        private UpdateCheckResult? _updateFound;
        private CancellationTokenSource? _updateCts;
        private DispatcherTimer? _updateTimer;

        // ------------------------------------------------------------------ settings fields

        private void LoadUpdateSettings(AppSettings s)
        {
            SetUpdAuto.IsChecked = s.UpdateAutoCheck;
            SetUpdHours.Text = s.UpdateIntervalHours.ToString(System.Globalization.CultureInfo.InvariantCulture);
            SetUpdUrl.Text = s.UpdateManifestUrl;
            SetUpdChannel.SelectedIndex = s.UpdateChannel.Equals("beta", StringComparison.OrdinalIgnoreCase) ? 1 : 0;
            UpdVersion.Text = "Installed version: " + AppServices.Updates.Current + (AppServices.Updates.RunningSigner.Length > 0 ? " · signed" : " · unsigned build (SHA-256 verification only)");
        }

        private bool ReadUpdateSettings(AppSettings next, out string error)
        {
            error = "";
            if (!int.TryParse(SetUpdHours.Text.Trim(), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var hours))
            {
                error = "The update interval must be a number of hours.";
                return false;
            }
            next.UpdateAutoCheck = SetUpdAuto.IsChecked == true;
            next.UpdateIntervalHours = hours;
            next.UpdateManifestUrl = SetUpdUrl.Text.Trim();
            next.UpdateChannel = (SetUpdChannel.SelectedItem as ComboBoxItem)?.Tag as string ?? "stable";
            return true;
        }

        // ------------------------------------------------------------------ startup + schedule

        /// <summary>Completes or rolls back the previous update, then starts the automatic check schedule.</summary>
        public void StartUpdateChecks()
        {
            try
            {
                var svc = AppServices.Updates;
                var action = svc.OnStartup();
                if (action == UpdateService.PendingAction.Rollback)
                {
                    svc.Rollback(AppPaths.AppDir);
                    Log.Warn("The last update did not start correctly; the previous version was restored.");
                    Dialogs.Info(this, "The last update did not work, so TIREXNET restored the previous version. Please restart TIREXNET.");
                    System.Windows.Application.Current.Shutdown();
                    return;
                }
                UpdateService.CleanupAside(AppPaths.AppDir);
                var healthTimer = new DispatcherTimer(TimeSpan.FromSeconds(25), DispatcherPriority.Background, (sender, _) =>
                {
                    ((DispatcherTimer)sender!).Stop();
                    svc.MarkHealthy();          // the new version reached its UI and stayed up: the update is confirmed
                }, Dispatcher);
                healthTimer.Start();
            }
            catch (Exception ex) when (ex is UpdateException or IOException or UnauthorizedAccessException)
            {
                Log.Error("Update housekeeping failed: " + ex.Message);
            }

            _updateTimer = new DispatcherTimer(TimeSpan.FromMinutes(30), DispatcherPriority.Background, (_, _) => MaybeAutoCheck(), Dispatcher);
            _updateTimer.Start();
            Dispatcher.BeginInvoke(new Action(MaybeAutoCheck), DispatcherPriority.Background);
        }

        private void MaybeAutoCheck()
        {
            var s = AppServices.Settings.Current;
            if (!s.UpdateAutoCheck || s.UpdateManifestUrl.Length == 0 || _updateCts != null) return;
            if (DateTime.TryParse(s.LastUpdateCheckUtc, null, System.Globalization.DateTimeStyles.RoundtripKind, out var last)
                && DateTime.UtcNow - last < TimeSpan.FromHours(Math.Max(1, s.UpdateIntervalHours))) return;
            _ = RunCheckAsync(manual: false);
        }

        // ------------------------------------------------------------------ check

        private void OnUpdateCheck(object sender, RoutedEventArgs e) => _ = RunCheckAsync(manual: true);

        private async Task RunCheckAsync(bool manual)
        {
            var s = AppServices.Settings.Current;
            _updateCts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            UpdCheckBtn.IsEnabled = false;
            if (manual) SetUpdateStatus("Checking for updates…", "MutedBrush");
            try
            {
                var r = await AppServices.Updates.CheckAsync(s.UpdateManifestUrl, s.UpdateChannel, _updateCts.Token);
                s.LastUpdateCheckUtc = DateTime.UtcNow.ToString("o");
                try { AppServices.Settings.Save(); } catch (IOException) { }
                _updateFound = r.Available ? r : null;
                if (r.Available) ShowFound(r);
                else
                {
                    UpdInstallBtn.Visibility = Visibility.Collapsed;
                    if (manual) SetUpdateStatus($"You are up to date ({r.Current}).", "MintBrush");
                }
            }
            catch (UpdateException ex)
            {
                Log.Warn("Update check failed: " + ex.Message);
                if (manual) SetUpdateStatus(ex.Message, "RedBrush");
            }
            catch (OperationCanceledException)
            {
                if (manual) SetUpdateStatus("The update check timed out.", "RedBrush");
            }
            finally
            {
                _updateCts?.Dispose();
                _updateCts = null;
                UpdCheckBtn.IsEnabled = true;
            }
        }

        private void ShowFound(UpdateCheckResult r)
        {
            var rel = r.Release!;
            var sb = new StringBuilder();
            sb.AppendLine($"Version {r.Latest} is available ({r.Channel}). You have {r.Current}. Download size: {rel.Size / 1048576.0:0.0} MB.");
            foreach (var n in rel.Notes.Take(12)) sb.AppendLine("• " + n);
            SetUpdateStatus(sb.ToString().TrimEnd(), "AmberBrush");
            UpdInstallBtn.Visibility = Visibility.Visible;
        }

        // ------------------------------------------------------------------ download + install

        private async void OnUpdateInstall(object sender, RoutedEventArgs e)
        {
            var found = _updateFound;
            if (found?.Release == null || _updateCts != null) return;
            var rel = found.Release;
            if (!Dialogs.Confirm(this, $"Install TIREXNET {found.Latest} now?\n\nYour profiles and settings are kept. Any active connection will be disconnected and TIREXNET will restart.")) return;

            _updateCts = new CancellationTokenSource();
            UpdInstallBtn.Visibility = Visibility.Collapsed;
            UpdCancelBtn.Visibility = Visibility.Visible;
            UpdProgress.Visibility = Visibility.Visible;
            UpdCheckBtn.IsEnabled = false;
            string? file = null;
            try
            {
                var progress = new Progress<(long done, long total)>(p =>
                {
                    UpdProgress.Value = p.total == 0 ? 0 : p.done * 100.0 / p.total;
                    SetUpdateStatus($"Downloading… {p.done / 1048576.0:0.0} of {p.total / 1048576.0:0.0} MB", "MutedBrush");
                });
                file = await AppServices.Updates.DownloadAsync(rel, progress, _updateCts.Token);
                SetUpdateStatus("Verified (SHA-256" + (AppServices.Updates.RunningSigner.Length > 0 ? " and digital signature" : "") + "). Installing…", "MintBrush");

                AppServices.Updates.PrepareInstall(AppPaths.AppDir, found.Latest);
                AppServices.Updates.VerifyFile(file, rel);      // re-check right before launch: the file sits in a user-writable folder
                try { AppServices.Manager.ShutdownBlocking(); } catch (Exception ex) { Log.Warn("Disconnect before update: " + ex.Message); }
                Process.Start(new ProcessStartInfo(file, UpdateService.InstallerArguments) { UseShellExecute = true });
                System.Windows.Application.Current.Shutdown();
            }
            catch (UpdateException ex)
            {
                Log.Error("Update failed: " + ex.Message);
                SetUpdateStatus(ex.Message + (ex.Failure == UpdateFailure.Network ? "" : "\nThe update was not installed."), "RedBrush");
                UpdInstallBtn.Visibility = ex.Failure is UpdateFailure.Network ? Visibility.Visible : Visibility.Collapsed;
            }
            catch (OperationCanceledException)
            {
                SetUpdateStatus("Download cancelled. It will resume next time.", "AmberBrush");
                UpdInstallBtn.Visibility = Visibility.Visible;
            }
            catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or IOException or UnauthorizedAccessException)
            {
                Log.Error("Update could not start: " + ex.Message);
                SetUpdateStatus("The installer could not be started: " + ex.Message, "RedBrush");
            }
            finally
            {
                _updateCts?.Dispose();
                _updateCts = null;
                UpdCancelBtn.Visibility = Visibility.Collapsed;
                UpdProgress.Visibility = Visibility.Collapsed;
                UpdCheckBtn.IsEnabled = true;
            }
        }

        private void OnUpdateCancel(object sender, RoutedEventArgs e) => _updateCts?.Cancel();

        private void SetUpdateStatus(string text, string brush)
        {
            UpdStatus.Text = text;
            UpdStatus.Foreground = (Brush)FindResource(brush);
        }
    }
}

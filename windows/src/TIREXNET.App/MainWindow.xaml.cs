using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using TIREXNET.Core;
using TIREXNET.Core.Engines;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;
using TIREXNET.Core.Services;
using DiagUtil = TIREXNET.Core.Services.Diagnostics;

namespace TIREXNET.UI
{
    public partial class MainWindow : Window
    {
        private readonly DispatcherTimer _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        private System.Windows.Forms.NotifyIcon? _tray;
        private System.Windows.Forms.ToolStripMenuItem? _trayToggle;
        private System.Drawing.Icon? _icon;
        private bool _exiting;
        private bool _ready;
        private bool _loading = true;
        private bool _syncing;
        private bool _balloonShown;
        private System.Windows.Forms.ToolStripMenuItem? _trayStatus;
        private ConnectionState? _lastUiState;
        private readonly List<(System.Windows.Shapes.Ellipse Star, double Fx, double Fy)> _stars = new();

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        public MainWindow()
        {
            InitializeComponent();
            _ready = true;
            SizeChanged += (_, _) => PlaceStars();
            VersionText.Text = "v" + (typeof(MainWindow).Assembly.GetName().Version?.ToString(3) ?? "1.0.0");
            AdminBadge.Text = Elevation.IsAdministrator() ? "Running as administrator" : "Standard user";
            LoadIcon();
            CreateTray();

            AppServices.Profiles.Changed += () => Dispatcher.BeginInvoke(new Action(RefreshProfiles));
            AppServices.Manager.Changed += () => Dispatcher.BeginInvoke(new Action(UpdateConnectionUi));
            Log.LineAdded += OnLogLine;
            _timer.Tick += (_, _) => UpdateLive();
            _timer.Start();

            LoadSettingsToUi();
            RefreshProfiles();
            RefreshAccountView();
            UpdateConnectionUi();
            LogBox.Text = string.Join("\n", Log.Snapshot()) + "\n";
            _loading = false;
            Log.Info("TIREXNET started.");
            if (AppServices.Profiles.LoadFailed)
                Log.Warn("Saved profiles could not be read. They may belong to a different Windows user.");

            Dispatcher.BeginInvoke(new Action(async () => await StartupTasksAsync()), DispatcherPriority.ApplicationIdle);
        }

        /// <summary>Creates the window handle without showing it (start minimized to the tray).</summary>
        public void PrepareHidden() => new WindowInteropHelper(this).EnsureHandle();

        private Window? OwnerOrNull => IsVisible ? this : null;

        // ------------------------------------------------------------------ startup / shutdown

        private async Task StartupTasksAsync()
        {
            try
            {
                SystemProxy.RestoreIfNeeded();
                if (RunState.HasPending())
                {
                    if (Elevation.IsAdministrator())
                    {
                        var r = await Recovery.CleanupAsync();
                        Log.Info("Cleaned up a previous session: " + r);
                    }
                    else if (Dialogs.Confirm(OwnerOrNull, "A previous TIREXNET session may have left network changes (routes or a tunnel adapter).\n\nRestart as administrator to clean them up now?"))
                    {
                        if (Elevation.RestartElevated("--elevated")) { ExitApplication(); return; }
                    }
                }
                if (TirexApp.AutoConnect) await ConnectSelectedAsync();
            }
            catch (Exception ex)
            {
                Log.Error("Startup task failed: " + ex.Message);
            }
        }

        private void OnSourceInitialized(object? sender, EventArgs e)
        {
            try
            {
                var hwnd = new WindowInteropHelper(this).Handle;
                var on = 1;
                if (DwmSetWindowAttribute(hwnd, 20, ref on, sizeof(int)) != 0) DwmSetWindowAttribute(hwnd, 19, ref on, sizeof(int));
            }
            catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException)
            {
                // Older Windows builds: keep the default title bar.
            }
        }

        private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
        {
            if (_exiting) return;
            e.Cancel = true;
            if (AppServices.Settings.Current.CloseToTray)
            {
                Hide();
                if (!_balloonShown)
                {
                    _balloonShown = true;
                    _tray?.ShowBalloonTip(2500, "TIREXNET", "TIREXNET keeps running in the system tray.", System.Windows.Forms.ToolTipIcon.Info);
                }
            }
            else
            {
                ExitApplication();
            }
        }

        private void OnStateChanged(object? sender, EventArgs e)
        {
            if (WindowState == WindowState.Minimized) Hide();
        }

        private void ExitApplication()
        {
            if (_exiting) return;
            _exiting = true;
            _timer.Stop();
            try { AppServices.Manager.ShutdownBlocking(); } catch (Exception ex) { Log.Error("Shutdown: " + ex.Message); }
            if (_tray != null) { _tray.Visible = false; _tray.Dispose(); _tray = null; }
            System.Windows.Application.Current.Shutdown();
        }

        // ------------------------------------------------------------------ tray

        private void LoadIcon()
        {
            try
            {
                var exe = Environment.ProcessPath;
                if (exe == null) return;
                _icon = System.Drawing.Icon.ExtractAssociatedIcon(exe);
                if (_icon != null)
                    Icon = Imaging.CreateBitmapSourceFromHIcon(_icon.Handle, Int32Rect.Empty, System.Windows.Media.Imaging.BitmapSizeOptions.FromEmptyOptions());
            }
            catch (Exception ex)
            {
                Log.Warn("Could not load the application icon: " + ex.Message);
            }
        }

        private void CreateTray()
        {
            var menu = new System.Windows.Forms.ContextMenuStrip { Renderer = new TrayRenderer(), ShowImageMargin = false };
            menu.Font = new System.Drawing.Font("Segoe UI", 9.5f);
            _trayStatus = new System.Windows.Forms.ToolStripMenuItem("TIREXNET — Disconnected") { Enabled = false };
            menu.Items.Add(_trayStatus);
            menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
            menu.Items.Add("Open TIREXNET", null, (_, _) => ShowFromTray());
            _trayToggle = new System.Windows.Forms.ToolStripMenuItem("Connect");
            _trayToggle.Click += async (_, _) => await ToggleConnectionAsync();
            menu.Items.Add(_trayToggle);
            menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
            menu.Items.Add("Exit", null, (_, _) => ExitApplication());
            _tray = new System.Windows.Forms.NotifyIcon
            {
                Icon = _icon ?? System.Drawing.SystemIcons.Application,
                Text = "TIREXNET",
                Visible = true,
                ContextMenuStrip = menu,
            };
            _tray.DoubleClick += (_, _) => ShowFromTray();
        }

        /// <summary>Called for a clicked tirexnet:// link, or (link == null) when a second launch just wants the window.</summary>
        public void HandleActivation(string? link)
        {
            ShowFromTray();
            if (link == null) return;
            Log.Info("Opening a tirexnet:// link from another application.");
            ImportText(link, null);
        }

        private void ShowFromTray()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
        }

        // ------------------------------------------------------------------ navigation

        private void OnNavChecked(object sender, RoutedEventArgs e)
        {
            if (!_ready) return;
            var tag = (sender as RadioButton)?.Tag as string;
            PageDashboard.Visibility = tag == "Dashboard" ? Visibility.Visible : Visibility.Collapsed;
            PageProfiles.Visibility = tag == "Profiles" ? Visibility.Visible : Visibility.Collapsed;
            PageAccount.Visibility = tag == "Account" ? Visibility.Visible : Visibility.Collapsed;
            PageSettings.Visibility = tag == "Settings" ? Visibility.Visible : Visibility.Collapsed;
            PageLogs.Visibility = tag == "Logs" ? Visibility.Visible : Visibility.Collapsed;
            var page = tag switch { "Dashboard" => (UIElement)PageDashboard, "Profiles" => PageProfiles, "Account" => PageAccount, "Settings" => PageSettings, _ => PageLogs };
            page.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
            if (tag == "Settings") RefreshEngineStatus();
            if (tag == "Logs") LogBox.ScrollToEnd();
        }

        // ------------------------------------------------------------------ connection

        private async void OnConnectClick(object sender, RoutedEventArgs e) => await ToggleConnectionAsync();

        private async Task ToggleConnectionAsync()
        {
            var state = AppServices.Manager.State;
            if (state == ConnectionState.Connected || state == ConnectionState.Connecting)
                await AppServices.Manager.DisconnectAsync();
            else if (state != ConnectionState.Disconnecting)
                await ConnectSelectedAsync();
        }

        private async Task ConnectSelectedAsync()
        {
            var profile = AppServices.Profiles.Selected;
            if (profile == null)
            {
                Dialogs.Error(OwnerOrNull, "Add or select a profile first (see the Profiles page).");
                return;
            }
            var settings = AppServices.Settings.Current;
            var unavailable = EngineCatalog.UnavailableReason(profile, settings);
            if (unavailable != null)
            {
                Dialogs.Error(OwnerOrNull, unavailable);
                return;
            }
            var needsAdmin = EngineCatalog.NeedsAdmin(profile, settings);
            if (needsAdmin && !Elevation.IsAdministrator())
            {
                var why = profile.Engine is EngineKind.Xray or EngineKind.SlipNet
                    ? "VPN mode needs administrator rights to create the TIREXNET network adapter and change routes."
                    : $"{EngineCatalog.Get(profile.Engine).Name} needs administrator rights to create its network adapter and routes.";
                if (Dialogs.Confirm(OwnerOrNull, why + "\n\nThe adapter, service and routes exist only while you are connected and are removed when you disconnect.\n\nRestart TIREXNET as administrator now?"))
                    RestartAsAdmin(true);
                return;
            }
            AppServices.Settings.Current.LastProfileId = profile.Id;
            await AppServices.Manager.ConnectAsync(profile, settings);
        }

        private void RestartAsAdmin(bool autoConnect)
        {
            if (AppServices.Manager.State is ConnectionState.Connected or ConnectionState.Connecting) return;
            var args = "--elevated" + (autoConnect ? " --autoconnect" : "");
            if (Elevation.RestartElevated(args)) ExitApplication();
            else Dialogs.Info(OwnerOrNull, "Administrator permission was not granted, so TIREXNET stays in standard mode.");
        }

        private void OnRestartAdmin(object sender, RoutedEventArgs e) => RestartAsAdmin(true);

        private static System.Windows.Media.Color Rgb(byte r, byte g, byte b, byte a = 255) =>
            System.Windows.Media.Color.FromArgb(a, r, g, b);

        private System.Windows.Media.Color ResColor(string brushKey) => ((SolidColorBrush)FindResource(brushKey)).Color;

        private void UpdateConnectionUi()
        {
            var m = AppServices.Manager;
            var st = m.State;
            var selected = AppServices.Profiles.Selected;
            string headline, label, detail, chip, chipBrush;
            switch (st)
            {
                case ConnectionState.Connected:
                    headline = "Connection secured"; label = "DISCONNECT"; chip = "SECURE"; chipBrush = "MintBrush";
                    detail = string.IsNullOrWhiteSpace(m.Message) ? $"Connected through {selected?.Name ?? "your server"}." : m.Message;
                    break;
                case ConnectionState.Connecting:
                    headline = "Connecting…"; label = "CANCEL"; chip = "CONNECTING"; chipBrush = "CyanBrush";
                    detail = string.IsNullOrWhiteSpace(m.Message) ? "Establishing a secure tunnel…" : m.Message;
                    break;
                case ConnectionState.Disconnecting:
                    headline = "Closing connection…"; label = "PLEASE WAIT"; chip = "CLOSING"; chipBrush = "IceBrush";
                    detail = string.IsNullOrWhiteSpace(m.Message) ? "Restoring your network settings…" : m.Message;
                    break;
                case ConnectionState.Error:
                    headline = "Connection failed"; label = "RETRY"; chip = "ERROR"; chipBrush = "RedBrush";
                    detail = string.IsNullOrWhiteSpace(m.Message) ? "The connection could not be established." : m.Message;
                    break;
                default:
                    headline = "Start your connection"; label = "CONNECT"; chip = "OFFLINE"; chipBrush = "MutedBrush";
                    detail = selected == null ? "Add or select a server to begin." : $"Press the orb to connect through {selected.Name}.";
                    break;
            }
            StatusText.Text = headline;
            // Engine errors can be several lines long: keep the card readable, the full text stays in the tooltip and the log.
            StatusDetail.ToolTip = detail;
            StatusDetail.Text = detail.Length > 260 ? detail[..260].TrimEnd() + "…" : detail;
            ConnectLabel.Text = label;
            var chipColor = ResColor(chipBrush);
            StatusChipText.Text = chip;
            StatusChipText.Foreground = (Brush)FindResource(chipBrush);
            StatusChip.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromArgb(150, chipColor.R, chipColor.G, chipColor.B));
            StatusChip.Background = new SolidColorBrush(System.Windows.Media.Color.FromArgb(34, chipColor.R, chipColor.G, chipColor.B));
            SideDot.Fill = (Brush)FindResource(st == ConnectionState.Disconnected ? "DimBrush" : chipBrush);
            SideStatus.Text = st switch { ConnectionState.Connected => "Connected", ConnectionState.Connecting => "Connecting…", ConnectionState.Disconnecting => "Closing…", ConnectionState.Error => "Error", _ => "Offline" };
            ApplyOrb(st);
            ConnectButton.IsEnabled = st != ConnectionState.Disconnecting;
            AdminButton.Visibility = m.ElevationNeeded ? Visibility.Visible : Visibility.Collapsed;
            HeroTimer.Visibility = st == ConnectionState.Connected ? Visibility.Visible : Visibility.Collapsed;

            var idle = st is ConnectionState.Disconnected or ConnectionState.Error;
            ModeProxy.IsEnabled = idle;
            ModeVpn.IsEnabled = idle && XrayEngine.IsVpnRuntimeAvailable;
            DashProfileList.IsEnabled = idle;

            if (_trayToggle != null) _trayToggle.Text = idle ? "Connect" : "Disconnect";
            var trayState = st == ConnectionState.Connected ? "Connected" : (idle ? "Disconnected" : "Working…");
            if (_trayStatus != null) _trayStatus.Text = "TIREXNET — " + trayState + (st == ConnectionState.Connected && selected != null ? " · " + selected.Name : "");
            if (_tray != null)
            {
                var tip = "TIREXNET — " + trayState;
                _tray.Text = tip.Length > 63 ? tip[..63] : tip; // NotifyIcon.Text throws above 63 characters
            }
            if (st == ConnectionState.Error && !IsVisible)
                _tray?.ShowBalloonTip(4000, "TIREXNET", string.IsNullOrWhiteSpace(m.Message) ? "Connection failed." : Redactor.Redact(m.Message), System.Windows.Forms.ToolTipIcon.Warning);
            UpdateLive();
            _lastUiState = st;
        }

        /// <summary>Pastel sphere colours per state (ConnectOrb.kt), warm/cool halo, rim, spinner and halo pulse while busy.</summary>
        private void ApplyOrb(ConnectionState st)
        {
            System.Windows.Media.Color c1, c2, c3, rim, halo;
            switch (st)
            {
                case ConnectionState.Connected:
                    c1 = Rgb(159, 213, 204); c2 = Rgb(194, 213, 230); c3 = Rgb(181, 190, 222);
                    rim = Rgb(35, 159, 120, 105); halo = Rgb(52, 202, 151, 90); break;
                case ConnectionState.Connecting:
                    c1 = Rgb(167, 193, 228); c2 = Rgb(224, 199, 204); c3 = Rgb(246, 183, 126);
                    rim = Rgb(54, 125, 204, 100); halo = Rgb(55, 151, 244, 90); break;
                case ConnectionState.Disconnecting:
                    c1 = Rgb(179, 195, 225); c2 = Rgb(218, 202, 211); c3 = Rgb(239, 190, 150);
                    rim = Rgb(54, 125, 204, 70); halo = Rgb(157, 207, 250, 80); break;
                case ConnectionState.Error:
                    c1 = Rgb(194, 184, 207); c2 = Rgb(228, 188, 182); c3 = Rgb(238, 169, 151);
                    rim = Rgb(244, 105, 119, 130); halo = Rgb(244, 105, 119, 80); break;
                default:
                    c1 = Rgb(177, 190, 222); c2 = Rgb(218, 202, 210); c3 = Rgb(247, 181, 119);
                    rim = Rgb(255, 255, 255, 60); halo = Rgb(247, 181, 119, 70); break;
            }
            var body = new RadialGradientBrush { GradientOrigin = new System.Windows.Point(0.35, 0.3), Center = new System.Windows.Point(0.45, 0.45), RadiusX = 0.72, RadiusY = 0.72 };
            body.GradientStops.Add(new GradientStop(c1, 0.0));
            body.GradientStops.Add(new GradientStop(c2, 0.5));
            body.GradientStops.Add(new GradientStop(c3, 0.85));
            body.GradientStops.Add(new GradientStop(Rgb(238, 206, 185), 1.0));
            OrbBody.Fill = body;
            OrbRim.Stroke = new SolidColorBrush(rim);
            var glow = new RadialGradientBrush();
            glow.GradientStops.Add(new GradientStop(halo, 0.0));
            glow.GradientStops.Add(new GradientStop(halo, 0.72));
            glow.GradientStops.Add(new GradientStop(System.Windows.Media.Color.FromArgb(0, halo.R, halo.G, halo.B), 1.0));
            OrbHalo.Fill = glow;

            var busy = st is ConnectionState.Connecting or ConnectionState.Disconnecting;
            if (busy)
            {
                OrbSpinner.Stroke = (Brush)FindResource(st == ConnectionState.Connecting ? "CyanBrush" : "IceBrush");
                OrbSpinner.Visibility = Visibility.Visible;
                SpinRot.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1.6)) { RepeatBehavior = RepeatBehavior.Forever });
                OrbHalo.BeginAnimation(OpacityProperty, new DoubleAnimation(0.45, 1, TimeSpan.FromSeconds(0.7)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever });
            }
            else
            {
                SpinRot.BeginAnimation(RotateTransform.AngleProperty, null);
                OrbHalo.BeginAnimation(OpacityProperty, null);
                OrbHalo.Opacity = 1;
                OrbSpinner.Visibility = Visibility.Collapsed;
            }
            if (_lastUiState != st)
                OrbBody.BeginAnimation(OpacityProperty, new DoubleAnimation(0.55, 1, TimeSpan.FromMilliseconds(320)));
        }

        private void AnimateOrbScale(double to, double ms)
        {
            var a = new DoubleAnimation(to, TimeSpan.FromMilliseconds(ms)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
            OrbHover.BeginAnimation(ScaleTransform.ScaleXProperty, a);
            OrbHover.BeginAnimation(ScaleTransform.ScaleYProperty, a);
        }

        private void OnOrbEnter(object sender, System.Windows.Input.MouseEventArgs e) => AnimateOrbScale(1.035, 160);
        private void OnOrbLeave(object sender, System.Windows.Input.MouseEventArgs e) => AnimateOrbScale(1.0, 200);
        private void OnOrbDown(object sender, MouseButtonEventArgs e) => AnimateOrbScale(0.96, 90);
        private void OnOrbUp(object sender, MouseButtonEventArgs e) => AnimateOrbScale(ConnectButton.IsMouseOver ? 1.035 : 1.0, 160);

        // ------------------------------------------------------------------ ambient stars (AmbientBackground.kt)

        private void OnWindowLoaded(object sender, RoutedEventArgs e)
        {
            if (_stars.Count > 0) return;
            var rnd = new Random(11);
            for (var i = 0; i < 22; i++)
            {
                var size = 1.5 + rnd.NextDouble() * 2.0;
                var star = new System.Windows.Shapes.Ellipse { Width = size, Height = size, Fill = (Brush)FindResource("IceBrush"), Opacity = 0.2 };
                StarsCanvas.Children.Add(star);
                _stars.Add((star, rnd.NextDouble(), rnd.NextDouble()));
                star.BeginAnimation(OpacityProperty, new DoubleAnimation(0.12, 0.85, TimeSpan.FromSeconds(2 + rnd.NextDouble() * 3))
                {
                    AutoReverse = true,
                    RepeatBehavior = RepeatBehavior.Forever,
                    BeginTime = TimeSpan.FromSeconds(rnd.NextDouble() * 3),
                });
            }
            PlaceStars();
        }

        private void PlaceStars()
        {
            var w = StarsCanvas.ActualWidth;
            var h = StarsCanvas.ActualHeight;
            if (w <= 0 || h <= 0) return;
            foreach (var (star, fx, fy) in _stars)
            {
                System.Windows.Controls.Canvas.SetLeft(star, fx * w);
                System.Windows.Controls.Canvas.SetTop(star, fy * h);
            }
        }

        private void UpdateLive()
        {
            var m = AppServices.Manager;
            if (m.State == ConnectionState.Connected && m.ConnectedSinceUtc.HasValue)
            {
                var d = DateTime.UtcNow - m.ConnectedSinceUtc.Value;
                DurationText.Text = $"{(int)d.TotalHours:00}:{d.Minutes:00}:{d.Seconds:00}";
                HeroTimer.Text = DurationText.Text;
                DownText.Text = DiagUtil.FormatBytes(m.Traffic.DownloadBytesPerSecond) + "/s";
                UpText.Text = DiagUtil.FormatBytes(m.Traffic.UploadBytesPerSecond) + "/s";
                TotalText.Text = $"{DiagUtil.FormatBytes(m.Traffic.DownloadBytes)} / {DiagUtil.FormatBytes(m.Traffic.UploadBytes)}";
            }
            else
            {
                DurationText.Text = "00:00:00";
                HeroTimer.Text = "00:00:00";
                DownText.Text = "0 B/s";
                UpText.Text = "0 B/s";
                TotalText.Text = "0 B / 0 B";
            }
        }

        private void OnModeChanged(object sender, RoutedEventArgs e)
        {
            if (!_ready || _loading) return;
            var s = AppServices.Settings.Current;
            s.Mode = ModeVpn.IsChecked == true ? ConnectionMode.Vpn : ConnectionMode.Proxy;
            AppServices.Settings.Save();
            UpdateModeHint();
        }

        private void UpdateModeHint()
        {
            var p = AppServices.Profiles.Selected;
            if (p != null && p.Engine is not (EngineKind.Xray or EngineKind.SlipNet))
                ModeHint.Text = $"{EngineCatalog.Get(p.Engine).Name} profiles always run as a full-device VPN with their own tunnel (administrator required).";
            else if (ModeVpn.IsChecked == true)
                ModeHint.Text = "Full-device VPN: all IPv4 traffic goes through the tunnel (administrator required).";
            else
                ModeHint.Text = $"Local proxy only: SOCKS5 127.0.0.1:{AppServices.Settings.Current.SocksPort}. This is not a full-device VPN.";
        }

        // ------------------------------------------------------------------ profiles

        private void RefreshProfiles()
        {
            _syncing = true;
            try
            {
                var all = AppServices.Profiles.All().ToList();
                var selected = AppServices.Profiles.Selected;
                DashProfileList.ItemsSource = all;
                ProfilesList.ItemsSource = all;
                var match = selected == null ? null : all.FirstOrDefault(p => p.Id == selected.Id);
                DashProfileList.SelectedItem = match;
                ProfilesList.SelectedItem = match;
                SelectedName.Text = selected?.Name ?? "No profile selected";
                SelectedBadgeText.Text = selected?.ProtocolCode ?? "—";
                DashEmptyHint.Visibility = all.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
                ProfilesEmptyHint.Visibility = all.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
                SelectedInfo.Text = selected == null ? "Add one on the Profiles page." : selected.Summary;
                ProfileDetail.Text = selected == null ? "" :
                    $"{selected.Name} — {selected.Badge}, {selected.Protocol.ToUpperInvariant()} · Source: {selected.SourceLabel} · Endpoint: {selected.Endpoint}" +
                    (selected.ReadOnly ? " · Synced from your account (read-only)" : "");
                UpdateModeHint();
            }
            finally
            {
                _syncing = false;
            }
            if (_ready && !_loading) UpdateConnectionUi();
        }

        private void OnDashSelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_syncing) return;
            if (DashProfileList.SelectedItem is Profile p) AppServices.Profiles.SelectedId = p.Id;
        }

        private void OnProfilesSelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_syncing) return;
            if (ProfilesList.SelectedItem is Profile p) AppServices.Profiles.SelectedId = p.Id;
        }

        private Profile? PickedProfile()
        {
            if (ProfilesList.SelectedItem is Profile p) return p;
            Dialogs.Info(this, "Select a profile in the list first.");
            return null;
        }

        private void OnImportClipboard(object sender, RoutedEventArgs e)
        {
            string text;
            try { text = System.Windows.Clipboard.GetText(); }
            catch (Exception ex) when (ex is COMException or System.Runtime.InteropServices.ExternalException)
            {
                Dialogs.Error(this, "The clipboard could not be read. Try again.");
                return;
            }
            ImportText(text, null);
        }

        private void OnImportUri(object sender, RoutedEventArgs e)
        {
            var text = Dialogs.Prompt(this, "Add link", "Paste a vless://, vmess://, trojan:// or ss:// link (several links, one per line, are fine):", "", true);
            if (text != null) ImportText(text, null);
        }

        private void OnImportFile(object sender, RoutedEventArgs e)
        {
            var dlg = new Microsoft.Win32.OpenFileDialog
            {
                Title = "Import profile",
                Filter = "Profiles (*.conf;*.ovpn;*.json;*.txt)|*.conf;*.ovpn;*.json;*.txt|All files (*.*)|*.*",
            };
            if (dlg.ShowDialog(this) != true) return;
            try
            {
                var info = new FileInfo(dlg.FileName);
                if (info.Length > 2_000_000) { Dialogs.Error(this, "That file is too large to be a profile."); return; }
                ImportText(File.ReadAllText(dlg.FileName), dlg.FileName);
            }
            catch (IOException ex)
            {
                Dialogs.Error(this, "The file could not be read: " + ex.Message);
            }
        }

        private void ImportText(string? text, string? fileName)
        {
            var result = ProfileParser.ParseText(text, fileName);
            var good = new List<Profile>();
            var problems = new List<string>(result.Errors);
            foreach (var p in result.Profiles)
            {
                var v = ConfigValidator.Validate(p);
                if (v.Count == 0) good.Add(p); else problems.Add($"{p.Name}: {v[0]}");
            }
            var added = AppServices.Profiles.Import(good);
            var sb = new StringBuilder();
            if (added > 0) sb.AppendLine($"Imported {added} profile(s).");
            else if (good.Count > 0) sb.AppendLine("Those profiles are already in your list.");
            if (problems.Count > 0)
            {
                if (sb.Length > 0) sb.AppendLine();
                sb.AppendLine("Not imported:");
                foreach (var pr in problems.Take(8)) sb.AppendLine("• " + pr);
                if (problems.Count > 8) sb.AppendLine($"… and {problems.Count - 8} more.");
            }
            if (added > 0 && problems.Count == 0) Log.Info(sb.ToString().Trim());
            else if (problems.Count > 0 && added == 0 && good.Count == 0) Dialogs.Error(this, sb.ToString().Trim());
            else Dialogs.Info(this, sb.ToString().Trim());
        }

        private void OnRename(object sender, RoutedEventArgs e)
        {
            var p = PickedProfile();
            if (p == null) return;
            if (p.Protected) { Dialogs.Info(this, "This profile was shared as protected: it can be connected, disconnected and deleted, but not renamed."); return; }
            if (p.ReadOnly) { Dialogs.Info(this, "Profiles synced from your account are read-only."); return; }
            var name = Dialogs.Prompt(this, "Rename profile", "Profile name:", p.Name);
            if (!string.IsNullOrWhiteSpace(name)) AppServices.Profiles.Rename(p.Id, name!);
        }

        private bool _pinging;

        /// <summary>Measures every profile (TCP connect or ICMP, see PingService), refreshes the list and returns the best connectable one.</summary>
        private async Task<Profile?> RunPingAsync()
        {
            var all = AppServices.Profiles.All().ToList();
            if (all.Count == 0)
            {
                Dialogs.Info(this, "There are no profiles to test.");
                return null;
            }
            _pinging = true;
            PingAllButton.IsEnabled = false;
            SmartConnectButton.IsEnabled = false;
            ProfileDetail.Text = $"Testing {all.Count} profile(s)…";
            try
            {
                var results = await PingService.TestAllAsync(all);
                var best = PingService.Best(results, AppServices.Settings.Current);
                RefreshProfiles();
                var ok = results.Count(r => r.Result.Kind == PingKind.Ok);
                ProfileDetail.Text = $"Ping finished: {ok} of {results.Count} reachable." +
                    (best == null ? " No connectable profile responded." : $" Fastest: {best.Name} ({best.PingMs} ms). Measured by TCP connect or ICMP; this does not prove the proxy or tunnel works.");
                return best;
            }
            finally
            {
                _pinging = false;
                PingAllButton.IsEnabled = true;
                SmartConnectButton.IsEnabled = true;
            }
        }

        private async void OnPingAll(object sender, RoutedEventArgs e)
        {
            if (_pinging) return;
            await RunPingAsync();
        }

        private async void OnSmartConnect(object sender, RoutedEventArgs e)
        {
            if (_pinging) return;
            if (AppServices.Manager.State is ConnectionState.Connected or ConnectionState.Connecting)
            {
                Dialogs.Info(this, "Disconnect first, then use Smart connect to pick the fastest profile.");
                return;
            }
            var best = await RunPingAsync();
            if (best == null)
            {
                Dialogs.Error(this, "Smart connect found no reachable profile that this installation can run.");
                return;
            }
            AppServices.Profiles.SelectedId = best.Id;
            await ConnectSelectedAsync();
        }

        private void OnEdit(object sender, RoutedEventArgs e)
        {
            var p = PickedProfile();
            if (p == null) return;
            if (p.Protected) { Dialogs.Info(this, "This profile was shared as protected: it can be connected, disconnected and deleted, but not edited."); return; }
            if (p.ReadOnly) { Dialogs.Info(this, "Profiles synced from your account are read-only. Import a copy to customize it."); return; }
            var text = Dialogs.Prompt(this, "Edit profile", "Edit the configuration. It is shown here in clear text and stored encrypted.", p.Raw, true);
            if (text == null || text.Trim() == p.Raw.Trim()) return;
            var parsed = ProfileParser.ParseText(text, null);
            if (parsed.Profiles.Count != 1 || parsed.Profiles[0].Engine != p.Engine)
            {
                Dialogs.Error(this, parsed.Errors.FirstOrDefault() ?? "The edited text must contain exactly one profile of the same type.");
                return;
            }
            var np = parsed.Profiles[0];
            var problems = ConfigValidator.Validate(np);
            if (problems.Count > 0) { Dialogs.Error(this, problems[0]); return; }
            p.Raw = np.Raw; p.Protocol = np.Protocol; p.Server = np.Server; p.Port = np.Port;
            AppServices.Profiles.Update(p);
        }

        private void OnExport(object sender, RoutedEventArgs e)
        {
            var p = PickedProfile();
            if (p == null) return;
            if (p.Protected) { Dialogs.Info(this, "This profile was shared as protected: it can be connected, disconnected and deleted, but not exported or copied."); return; }
            if (!Dialogs.Confirm(this, "The exported file is NOT encrypted and contains your connection secrets. Keep it safe.\n\nContinue?")) return;
            var dlg = new Microsoft.Win32.SaveFileDialog { FileName = ProfileStore.ExportFileName(p), Title = "Export profile" };
            if (dlg.ShowDialog(this) != true) return;
            try { File.WriteAllText(dlg.FileName, ProfileStore.ExportText(p)); }
            catch (IOException ex) { Dialogs.Error(this, "Could not write the file: " + ex.Message); }
        }

        private void OnDelete(object sender, RoutedEventArgs e)
        {
            var p = PickedProfile();
            if (p == null) return;
            if (p.ReadOnly) { Dialogs.Info(this, "Profiles synced from your account cannot be deleted. Sign out to remove them."); return; }
            if (AppServices.Manager.ActiveProfile?.Id == p.Id && AppServices.Manager.State is ConnectionState.Connected or ConnectionState.Connecting)
            {
                Dialogs.Info(this, "Disconnect before deleting the active profile.");
                return;
            }
            if (Dialogs.Confirm(this, $"Delete \"{p.Name}\"?")) AppServices.Profiles.Remove(p.Id);
        }

        // ------------------------------------------------------------------ account

        private string PanelId => PanelAli.IsChecked == true ? ApiClient.PanelAliPanel : ApiClient.PanelPasarGuard;

        private void OnPanelChanged(object sender, RoutedEventArgs e)
        {
            if (!_ready) return;
            AccountMessage.Text = "";
            RefreshAccountView();
        }

        private void RefreshAccountView()
        {
            var s = AppServices.Sessions.Get(PanelId);
            PanelStatus.Text = s == null ? "Not signed in" : $"Signed in as {s.Username}";
            PanelStatus.Foreground = (Brush)FindResource(s == null ? "MutedBrush" : "MintBrush");
            LoginForm.Visibility = s == null ? Visibility.Visible : Visibility.Collapsed;
            AccountInfoPanel.Visibility = s == null ? Visibility.Collapsed : Visibility.Visible;
        }

        private void OnLoginKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) OnSignIn(sender, e);
        }

        private async void OnSignIn(object sender, RoutedEventArgs e)
        {
            var user = LoginUser.Text.Trim();
            var pass = LoginPass.Password;
            if (user.Length == 0 || pass.Length == 0) { AccountMessage.Text = "Enter your username and password."; return; }
            SignInButton.IsEnabled = false;
            SignInButton.Content = "Signing in…";
            try
            {
                var panel = PanelId;
                var token = await AppServices.Api.LoginAsync(user, pass, panel);
                AppServices.Sessions.Set(panel, token, user);
                LoginPass.Clear();
                RefreshAccountView();
                await SyncAsync();
            }
            catch (ApiException ex) when (ex.StatusCode is 401 or 403)
            {
                AccountMessage.Text = "The username or password is not correct.";
            }
            catch (ApiException ex)
            {
                AccountMessage.Text = "The server could not sign you in (" + ex.StatusCode + "). Try again later.";
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
            {
                AccountMessage.Text = "Could not reach the server. Check your internet connection and try again.";
            }
            finally
            {
                SignInButton.IsEnabled = true;
                SignInButton.Content = "Sign in";
            }
        }

        private async void OnSync(object sender, RoutedEventArgs e) => await SyncAsync();

        private async Task SyncAsync()
        {
            var panel = PanelId;
            var session = AppServices.Sessions.Get(panel);
            if (session == null) return;
            AccountMessage.Text = "Syncing…";
            try
            {
                var info = await AppServices.Api.GetAccountAsync(session.Token);
                var limit = info.LimitBytes > 0 ? DiagUtil.FormatBytes(info.LimitBytes) : "unlimited";
                AccountText.Text =
                    $"Account: {info.Username}\nSubscription: {info.PanelUsername} · {info.Status}\n" +
                    $"Usage: {DiagUtil.FormatBytes(info.UsedBytes)} of {limit}\n" +
                    $"Expires: {(string.IsNullOrEmpty(info.Expire) ? "no expiry date" : info.Expire)}";

                var configs = await AppServices.Api.GetConfigsAsync(session.Token, panel);
                var skipped = new List<string>();
                var profiles = ApiClient.ToProfiles(configs, panel, skipped);
                var valid = new List<Profile>();
                foreach (var p in profiles)
                {
                    var v = ConfigValidator.Validate(p);
                    if (v.Count == 0) valid.Add(p); else skipped.Add($"{p.Name}: {v[0]}");
                }
                AppServices.Profiles.ReplaceSynced(panel, valid);
                var msg = $"{valid.Count} server(s) synced.";
                if (skipped.Count > 0) msg += $" {skipped.Count} entr{(skipped.Count == 1 ? "y was" : "ies were")} skipped: " + skipped[0];
                AccountMessage.Text = msg;
                foreach (var s in skipped) Log.Warn("Sync skipped: " + s);
            }
            catch (ApiException ex) when (ex.StatusCode == 401)
            {
                AppServices.Sessions.Remove(panel);
                RefreshAccountView();
                AccountMessage.Text = "Your session expired. Please sign in again.";
            }
            catch (ApiException ex)
            {
                AccountMessage.Text = "The server answered with an error (" + ex.StatusCode + "). Try again later.";
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
            {
                AccountMessage.Text = "Could not reach the server. Check your internet connection.";
            }
        }

        private async void OnSignOut(object sender, RoutedEventArgs e)
        {
            var panel = PanelId;
            var session = AppServices.Sessions.Get(panel);
            if (session == null) return;
            if (AppServices.Manager.ActiveProfile?.Source == panel && AppServices.Manager.State is ConnectionState.Connected or ConnectionState.Connecting)
            {
                Dialogs.Info(this, "Disconnect before signing out.");
                return;
            }
            try { await AppServices.Api.LogoutAsync(session.Token); }
            catch (Exception ex) when (ex is ApiException or HttpRequestException or TaskCanceledException) { /* local sign-out still proceeds */ }
            AppServices.Sessions.Remove(panel);
            AppServices.Profiles.RemoveBySource(panel);
            AccountText.Text = "";
            AccountMessage.Text = "Signed out.";
            RefreshAccountView();
        }

        // ------------------------------------------------------------------ settings

        private void LoadSettingsToUi()
        {
            var s = AppServices.Settings.Current;
            SetSocks.Text = s.SocksPort.ToString(System.Globalization.CultureInfo.InvariantCulture);
            SetHttp.Text = s.HttpPort.ToString(System.Globalization.CultureInfo.InvariantCulture);
            SetEnableHttp.IsChecked = s.EnableHttpProxy;
            SetSysProxy.IsChecked = s.SetSystemProxy;
            SetDns.Text = s.Dns;
            SetMtu.Text = s.Mtu.ToString(System.Globalization.CultureInfo.InvariantCulture);
            SetBypass.Text = s.BypassNetworks;
            SetCloseToTray.IsChecked = s.CloseToTray;
            SetStartMin.IsChecked = s.StartMinimized;
            LoadUpdateSettings(s);
            var vpn = s.Mode == ConnectionMode.Vpn && XrayEngine.IsVpnRuntimeAvailable;
            ModeVpn.IsChecked = vpn;
            ModeProxy.IsChecked = !vpn;
            RefreshEngineStatus();
        }

        private void RefreshEngineStatus()
        {
            EngineStatus.Text = string.Join("\n", EngineCatalog.StatusLines());
        }

        private void OnDnsPreset(object sender, RoutedEventArgs e)
        {
            SetDns.Text = (sender as Button)?.Tag as string ?? "";
        }

        private void OnSaveSettings(object sender, RoutedEventArgs e)
        {
            if (AppServices.Manager.State is ConnectionState.Connected or ConnectionState.Connecting)
            {
                SettingsMessage.Text = "Disconnect before changing settings.";
                SettingsMessage.Foreground = (Brush)FindResource("AmberBrush");
                return;
            }
            var cur = AppServices.Settings.Current;
            if (!int.TryParse(SetSocks.Text.Trim(), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var socks) || !int.TryParse(SetHttp.Text.Trim(), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var http) || !int.TryParse(SetMtu.Text.Trim(), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var mtu))
            {
                SettingsMessage.Text = "Ports and MTU must be numbers.";
                SettingsMessage.Foreground = (Brush)FindResource("RedBrush");
                return;
            }
            var next = new AppSettings
            {
                Mode = ModeVpn.IsChecked == true ? ConnectionMode.Vpn : ConnectionMode.Proxy,
                SocksPort = socks,
                HttpPort = http,
                EnableHttpProxy = SetEnableHttp.IsChecked == true,
                SetSystemProxy = SetSysProxy.IsChecked == true,
                Dns = SetDns.Text.Trim(),
                Mtu = mtu,
                BypassNetworks = SetBypass.Text.Trim(),
                CloseToTray = SetCloseToTray.IsChecked == true,
                StartMinimized = SetStartMin.IsChecked == true,
                LastProfileId = cur.LastProfileId,
                LastUpdateCheckUtc = cur.LastUpdateCheckUtc,
            };
            if (!ReadUpdateSettings(next, out var updateError))
            {
                SettingsMessage.Text = updateError;
                SettingsMessage.Foreground = (Brush)FindResource("RedBrush");
                return;
            }
            var errors = ConfigValidator.ValidateSettings(next);
            if (errors.Count > 0)
            {
                SettingsMessage.Text = string.Join("\n", errors);
                SettingsMessage.Foreground = (Brush)FindResource("RedBrush");
                return;
            }
            AppServices.Settings.Save(next);
            SettingsMessage.Text = "Settings saved. They apply the next time you connect.";
            SettingsMessage.Foreground = (Brush)FindResource("MintBrush");
            UpdateModeHint();
        }

        // ------------------------------------------------------------------ logs

        private void OnLogLine(string line)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                LogBox.AppendText(line + "\n");
                if (LogBox.Text.Length > 300_000) LogBox.Text = LogBox.Text[^150_000..];
                LogBox.ScrollToEnd();
            }));
        }

        private async void OnDiagnostics(object sender, RoutedEventArgs e)
        {
            // async void: nothing may escape, or an unexpected exception would reach the dispatcher.
            DiagButton.IsEnabled = false;
            DiagButton.Content = "Running…";
            try
            {
                var text = await DiagUtil.RunAsync();
                LogBox.AppendText("\n" + text + "\n");
                LogBox.ScrollToEnd();
            }
            catch (Exception ex)
            {
                Log.Error("Diagnostics display failed: " + ex);
                var msg = Redactor.Redact(ex.Message);
                LogBox.AppendText("\nDiagnostics could not be completed: " + msg + "\n");
                Dialogs.Error(this, "Diagnostics could not be completed.\n\n" + msg + "\n\nDetails were written to the log.");
            }
            finally
            {
                DiagButton.IsEnabled = true;
                DiagButton.Content = "Run diagnostics";
            }
        }

        private void OnCopyLogs(object sender, RoutedEventArgs e)
        {
            try { System.Windows.Clipboard.SetText(LogBox.Text); }
            catch (Exception ex) when (ex is COMException or System.Runtime.InteropServices.ExternalException) { /* clipboard busy */ }
        }

        private void OnClearLogs(object sender, RoutedEventArgs e)
        {
            Log.Clear();
            LogBox.Clear();
        }

        private void OnOpenLogFolder(object sender, RoutedEventArgs e)
        {
            try { Process.Start(new ProcessStartInfo("explorer.exe", AppPaths.LogDir) { UseShellExecute = true }); }
            catch (Exception ex) when (ex is System.ComponentModel.Win32Exception) { Dialogs.Error(this, ex.Message); }
        }
    }
}

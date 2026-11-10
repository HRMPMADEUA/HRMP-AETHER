using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;

namespace TIREXNET.UI
{
    /// <summary>
    /// Glass dialogs in the TIREXNET look (info, error, confirm and text prompt). They replace the native MessageBox so
    /// that every dialog uses the same palette, corner radii and animations as the main window.
    /// </summary>
    internal static class Dialogs
    {
        private enum Kind { Info, Error, Question }

        public static void Info(Window? owner, string message, string title = "TIREXNET") =>
            ShowMessage(owner, title, message, Kind.Info, false);

        public static void Error(Window? owner, string message, string title = "TIREXNET") =>
            ShowMessage(owner, title, message, Kind.Error, false);

        public static bool Confirm(Window? owner, string message, string title = "TIREXNET") =>
            ShowMessage(owner, title, message, Kind.Question, true);

        /// <summary>Shows a text prompt (Add link / Edit profile / Rename). Returns null when cancelled.</summary>
        public static string? Prompt(Window? owner, string title, string message, string initial = "", bool multiline = false)
        {
            var box = new TextBox
            {
                Text = initial ?? "",
                AcceptsReturn = multiline,
                TextWrapping = multiline ? TextWrapping.NoWrap : TextWrapping.Wrap,
                VerticalScrollBarVisibility = multiline ? ScrollBarVisibility.Auto : ScrollBarVisibility.Disabled,
                HorizontalScrollBarVisibility = multiline ? ScrollBarVisibility.Auto : ScrollBarVisibility.Disabled,
                FontFamily = multiline ? new FontFamily("Consolas") : new FontFamily("Segoe UI"),
                FontSize = multiline ? 12.5 : 14,
                VerticalContentAlignment = multiline ? VerticalAlignment.Top : VerticalAlignment.Center,
            };
            var body = new Grid();
            body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            body.RowDefinitions.Add(new RowDefinition { Height = multiline ? new GridLength(1, GridUnitType.Star) : GridLength.Auto });
            var text = Caption(message);
            text.Margin = new Thickness(0, 0, 0, 12);
            body.Children.Add(text);
            Grid.SetRow(box, 1);
            body.Children.Add(box);
            if (!multiline) box.MinHeight = 40;

            string? result = null;
            var win = CreateShell(owner, title, multiline ? 720 : 520, multiline ? 540 : double.NaN, Kind.Info, body, out var buttons, out var closeWith);
            var ok = (Button)new Button { Content = "OK", MinWidth = 96, Style = St("NeonButton"), IsDefault = !multiline };
            var cancel = new Button { Content = "Cancel", MinWidth = 96, Style = St("GhostButton"), IsCancel = true };
            ok.Click += (_, _) => { result = box.Text; closeWith(true); };
            cancel.Click += (_, _) => closeWith(false);
            buttons.Children.Add(cancel);
            buttons.Children.Add(ok);
            win.Loaded += (_, _) => { box.Focus(); box.SelectAll(); };
            win.ShowDialog();
            return result;
        }

        // ------------------------------------------------------------------ internals

        private static bool ShowMessage(Window? owner, string title, string message, Kind kind, bool confirm)
        {
            var text = new TextBlock
            {
                Text = message,
                TextWrapping = TextWrapping.Wrap,
                Foreground = B("TextBrush"),
                FontSize = 13.5,
                LineHeight = 20,
            };
            var scroller = new ScrollViewer { Content = text, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = 340 };
            var win = CreateShell(owner, title, 480, double.NaN, kind, scroller, out var buttons, out var closeWith);
            var answer = false;
            if (confirm)
            {
                var no = new Button { Content = "No", MinWidth = 96, Style = St("GhostButton"), IsCancel = true };
                var yes = new Button { Content = "Yes", MinWidth = 96, Style = St("NeonButton"), IsDefault = true };
                no.Click += (_, _) => closeWith(false);
                yes.Click += (_, _) => { answer = true; closeWith(true); };
                buttons.Children.Add(no);
                buttons.Children.Add(yes);
            }
            else
            {
                var ok = new Button { Content = "OK", MinWidth = 96, Style = St("NeonButton"), IsDefault = true, IsCancel = true };
                ok.Click += (_, _) => closeWith(true);
                buttons.Children.Add(ok);
            }
            win.ShowDialog();
            return answer;
        }

        private static Window CreateShell(Window? owner, string title, double width, double height, Kind kind, UIElement content,
            out StackPanel buttons, out Action<bool> closeWith)
        {
            var accent = kind switch { Kind.Error => "RedBrush", Kind.Question => "AmberBrush", _ => "CyanBrush" };
            var accentBrush = B(accent);
            var accentColor = ((SolidColorBrush)accentBrush).Color;

            var win = new Window
            {
                WindowStyle = WindowStyle.None,
                AllowsTransparency = true,
                Background = Brushes.Transparent,
                ShowInTaskbar = false,
                ResizeMode = ResizeMode.NoResize,
                Title = title,
                FontFamily = new FontFamily("Segoe UI"),
                Width = width + 36,
                SizeToContent = double.IsNaN(height) ? SizeToContent.Height : SizeToContent.Manual,
                WindowStartupLocation = WindowStartupLocation.CenterScreen,
            };
            if (!double.IsNaN(height)) win.Height = height + 36;
            if (owner != null && owner.IsVisible)
            {
                win.Owner = owner;
                win.WindowStartupLocation = WindowStartupLocation.CenterOwner;
            }

            var scale = new ScaleTransform(0.96, 0.96);
            var card = new Border
            {
                Margin = new Thickness(18),
                CornerRadius = new CornerRadius(20),
                Background = B("GlassStrongBrush"),
                BorderBrush = B("StrokeBrush"),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(22, 18, 22, 18),
                RenderTransformOrigin = new Point(0.5, 0.5),
                RenderTransform = scale,
            };
            // The glass gradient is translucent; give the dialog an opaque navy base so text stays readable over any window.
            var root = new Border
            {
                CornerRadius = new CornerRadius(20),
                Background = B("Bg1"),
                Margin = new Thickness(18),
                Effect = new DropShadowEffect { BlurRadius = 26, ShadowDepth = 6, Opacity = 0.55, Color = Colors.Black },
            };
            card.Margin = new Thickness(0);
            root.Child = card;

            var grid = new Grid();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            var header = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 14), Background = Brushes.Transparent };
            var glyph = new System.Windows.Shapes.Path
            {
                Stroke = accentBrush,
                StrokeThickness = 2,
                StrokeStartLineCap = PenLineCap.Round,
                StrokeEndLineCap = PenLineCap.Round,
                Stretch = Stretch.None,
                Data = Geometry.Parse(kind switch
                {
                    Kind.Error => "M12,6.5 V13 M12,17 H12.01",
                    Kind.Question => "M9.3,9.2 A2.8,2.8 0 1 1 12,12.4 V13.6 M12,17.2 H12.01",
                    _ => "M12,10.5 V17 M12,7 H12.01",
                }),
            };
            var glyphHost = new Canvas { Width = 24, Height = 24 };
            glyphHost.Children.Add(glyph);
            var badge = new Grid { Width = 36, Height = 36 };
            badge.Children.Add(new System.Windows.Shapes.Ellipse
            {
                Fill = new SolidColorBrush(Color.FromArgb(40, accentColor.R, accentColor.G, accentColor.B)),
                Stroke = new SolidColorBrush(Color.FromArgb(150, accentColor.R, accentColor.G, accentColor.B)),
                StrokeThickness = 1.5,
            });
            glyphHost.HorizontalAlignment = HorizontalAlignment.Center;
            glyphHost.VerticalAlignment = VerticalAlignment.Center;
            badge.Children.Add(glyphHost);
            header.Children.Add(badge);
            header.Children.Add(new TextBlock
            {
                Text = title,
                Foreground = B("TextBrush"),
                FontSize = 17,
                FontWeight = FontWeights.SemiBold,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(12, 0, 0, 0),
            });
            header.MouseLeftButtonDown += (_, e) =>
            {
                try { win.DragMove(); }
                catch (InvalidOperationException) { /* button released before the drag started */ }
            };
            grid.Children.Add(header);

            Grid.SetRow((FrameworkElement)content, 1);
            grid.Children.Add(content);

            buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
            Grid.SetRow(buttons, 2);
            grid.Children.Add(buttons);
            card.Child = grid;

            win.Content = root;
            win.Opacity = 0;
            win.Loaded += (_, _) =>
            {
                win.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(150)));
                var a = new DoubleAnimation(0.96, 1, TimeSpan.FromMilliseconds(180)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
                scale.BeginAnimation(ScaleTransform.ScaleXProperty, a);
                scale.BeginAnimation(ScaleTransform.ScaleYProperty, a);
            };
            win.PreviewKeyDown += (_, e) =>
            {
                if (e.Key == Key.Escape) { win.DialogResult = false; e.Handled = true; }
            };
            closeWith = ok =>
            {
                try { win.DialogResult = ok; }
                catch (InvalidOperationException) { win.Close(); }
            };
            return win;
        }

        private static TextBlock Caption(string text) => new TextBlock
        {
            Text = text,
            TextWrapping = TextWrapping.Wrap,
            Foreground = B("MutedBrush"),
            FontSize = 13,
        };

        private static Brush B(string key) => (Brush)Application.Current.FindResource(key);

        private static Style St(string key) => (Style)Application.Current.FindResource(key);
    }
}

using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace TIREXNET.UI
{
    /// <summary>Small code-only dialogs in the TIREXNET look.</summary>
    internal static class Dialogs
    {
        public static void Info(Window? owner, string message, string title = "TIREXNET") =>
            System.Windows.MessageBox.Show(owner, message, title, MessageBoxButton.OK, MessageBoxImage.Information);

        public static void Error(Window? owner, string message, string title = "TIREXNET") =>
            System.Windows.MessageBox.Show(owner, message, title, MessageBoxButton.OK, MessageBoxImage.Warning);

        public static bool Confirm(Window? owner, string message, string title = "TIREXNET") =>
            System.Windows.MessageBox.Show(owner, message, title, MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes;

        /// <summary>Shows a text prompt. Returns null when cancelled.</summary>
        public static string? Prompt(Window? owner, string title, string message, string initial = "", bool multiline = false)
        {
            var win = new Window
            {
                Title = title,
                Owner = owner,
                WindowStartupLocation = owner == null ? WindowStartupLocation.CenterScreen : WindowStartupLocation.CenterOwner,
                Width = multiline ? 640 : 480,
                SizeToContent = multiline ? SizeToContent.Manual : SizeToContent.Height,
                Height = multiline ? 460 : double.NaN,
                Background = (Brush)System.Windows.Application.Current.Resources["Bg1"],
                Foreground = (Brush)System.Windows.Application.Current.Resources["TextBrush"],
                ShowInTaskbar = false,
                ResizeMode = multiline ? ResizeMode.CanResize : ResizeMode.NoResize,
            };
            var grid = new Grid { Margin = new Thickness(18) };
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            grid.RowDefinitions.Add(new RowDefinition { Height = multiline ? new GridLength(1, GridUnitType.Star) : GridLength.Auto });
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            var label = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 10) };
            Grid.SetRow(label, 0);
            var box = new System.Windows.Controls.TextBox
            {
                Text = initial,
                AcceptsReturn = multiline,
                TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap,
                VerticalScrollBarVisibility = multiline ? ScrollBarVisibility.Auto : ScrollBarVisibility.Disabled,
                FontFamily = multiline ? new FontFamily("Consolas") : new FontFamily("Segoe UI"),
            };
            Grid.SetRow(box, 1);

            var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 14, 0, 0) };
            var ok = new System.Windows.Controls.Button { Content = "OK", Style = (Style)System.Windows.Application.Current.Resources["NeonButton"], MinWidth = 80, IsDefault = !multiline };
            var cancel = new System.Windows.Controls.Button { Content = "Cancel", Style = (Style)System.Windows.Application.Current.Resources["GhostButton"], MinWidth = 80, IsCancel = true };
            string? result = null;
            ok.Click += (_, _) => { result = box.Text; win.DialogResult = true; };
            buttons.Children.Add(ok);
            buttons.Children.Add(cancel);
            Grid.SetRow(buttons, 2);

            grid.Children.Add(label);
            grid.Children.Add(box);
            grid.Children.Add(buttons);
            win.Content = grid;
            win.Loaded += (_, _) => { box.Focus(); box.SelectAll(); };
            return win.ShowDialog() == true ? result : null;
        }
    }
}

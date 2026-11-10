using System.Windows;
using System.Windows.Media;

namespace TIREXNET.UI
{
    /// <summary>Attached property that carries a 24x24 line-icon geometry for navigation buttons.</summary>
    public static class Ico
    {
        public static readonly DependencyProperty DataProperty =
            DependencyProperty.RegisterAttached("Data", typeof(Geometry), typeof(Ico), new PropertyMetadata(null));

        public static Geometry? GetData(DependencyObject d) => (Geometry?)d.GetValue(DataProperty);
        public static void SetData(DependencyObject d, Geometry? value) => d.SetValue(DataProperty, value);
    }
}

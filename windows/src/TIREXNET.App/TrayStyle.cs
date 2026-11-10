using System.Drawing;
using System.Windows.Forms;

namespace TIREXNET.UI
{
    /// <summary>Dark TIREXNET colours for the tray context menu (matches Palette.kt bg/text/stroke).</summary>
    internal sealed class TrayColorTable : ProfessionalColorTable
    {
        private static readonly Color Bg = Color.FromArgb(10, 29, 52);
        private static readonly Color Hot = Color.FromArgb(39, 123, 225);
        private static readonly Color Stroke = Color.FromArgb(76, 113, 165);

        public override Color ToolStripDropDownBackground => Bg;
        public override Color ImageMarginGradientBegin => Bg;
        public override Color ImageMarginGradientMiddle => Bg;
        public override Color ImageMarginGradientEnd => Bg;
        public override Color MenuBorder => Stroke;
        public override Color MenuItemBorder => Hot;
        public override Color MenuItemSelected => Color.FromArgb(24, 62, 108);
        public override Color MenuItemSelectedGradientBegin => Color.FromArgb(24, 62, 108);
        public override Color MenuItemSelectedGradientEnd => Color.FromArgb(24, 62, 108);
        public override Color SeparatorDark => Stroke;
        public override Color SeparatorLight => Stroke;
    }

    internal sealed class TrayRenderer : ToolStripProfessionalRenderer
    {
        public TrayRenderer() : base(new TrayColorTable()) { RoundedEdges = false; }

        protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
        {
            e.TextColor = e.Item.Enabled ? Color.FromArgb(244, 248, 255) : Color.FromArgb(111, 137, 166);
            base.OnRenderItemText(e);
        }
    }
}

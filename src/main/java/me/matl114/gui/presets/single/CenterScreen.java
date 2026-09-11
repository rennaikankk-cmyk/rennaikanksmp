package me.matl114.gui.presets.single;

import me.matl114.gui.GenericScreen;
import me.matl114.gui.WidgetUtils;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.modules.task.ClickGui;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public class CenterScreen extends GenericScreen {
    DrawableWidget widget;

    public CenterScreen(DrawableWidget widget) {
        super(Text.empty(), 0, 0);
        this.widget = widget;
    }

    @Override
    protected void init0() {
        super.init0();
        this.x = 0;
        this.y = 0;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        if (client.world == null) {
            super.renderBackground(context, mouseX, mouseY, deltaTicks);
        } else if (ClickGui.INSTANCE.dimBackground.get()) {
            // centered popups float over the world: dim it so they stay readable
            context.fill(0, 0, this.width, this.height, ClickGui.dimOverlayColor());
        }
    }

    @Override
    protected void init() {
        super.init();
        DrawableWidget dynamic = WidgetUtils.createCenterScreenWidget(widget, this.width, this.height);
        dynamic.addTo(this);
    }
}

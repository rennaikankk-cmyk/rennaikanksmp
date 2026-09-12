package me.matl114.gui.complex.clickGui;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import me.matl114.gui.GenericScreen;
import me.matl114.gui.basic.*;
import me.matl114.gui.elements.TabButtonElement;
import me.matl114.hacks.modules.task.ClickGui;
import me.matl114.utils.ChatUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ClickGuiMainScreen extends GenericScreen {
    Map<String, Function<Screen, DrawableWidget>> widgets;
    DrawableWidget widget;
    String selecting;

    public ClickGuiMainScreen(Map<String, Function<Screen, DrawableWidget>> widgets) {
        super(Text.empty(), 0, 0);
        this.widgets = widgets;
        String val = this.widgets.keySet().iterator().next();
        setGlobal(val);
    }

    protected void init0() {
        super.init0();
        // FULL SCREEN
        this.x = 0;
        this.y = 0;
    }

    public static final int BUTTON_HEIGHT = 12;
    public static final int BUTTON_MAX_WIDTH = 60;

    protected void setGlobal(String string) {
        if (!Objects.equals(string, selecting)) {
            selecting = string;
            widget = widgets.get(selecting).apply(this);
            if (widgetDelegate != null) {
                widgetDelegate.setContentDelegate(widget);
            }
        }
    }

    ContentDelegateWidget<DrawableWidget> widgetDelegate;

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        if (client.world == null) {
            super.renderBackground(context, mouseX, mouseY, deltaTicks);
        } else if (ClickGui.INSTANCE.dimBackground.get()) {
            // in-world: dim the game instead of rendering nothing, so the
            // floating module windows stay readable over bright scenes
            context.fill(0, 0, this.width, this.height, ClickGui.dimOverlayColor());
        }
    }

    @Override
    protected void init() {
        super.init();
        // dark toolbar strip behind the tab row
        DisplayWidget.instance(0, 0, this.width, BUTTON_HEIGHT)
                .setRenderHandler(new AbstractElement()
                        .combineRender((element, context, mouseX, mouseY, delta, alpha, shouldHighlight) -> {
                            context.fill(0, 0, element.getTextureWidth(), element.getTextureHeight(), 0, 0xE015171C);
                            context.fill(
                                    0,
                                    element.getTextureHeight() - 1,
                                    element.getTextureWidth(),
                                    element.getTextureHeight(),
                                    0,
                                    0x30FFFFFF);
                        }))
                .addTo(this);
        int size = widgets.size();
        int blank;
        int width;
        if (size * BUTTON_MAX_WIDTH > this.width) {
            blank = 0;
            width = this.width / size;
        } else {
            blank = (this.width - size * BUTTON_MAX_WIDTH) / 2;
            width = BUTTON_MAX_WIDTH;
        }
        int cnt = 0;
        for (String entry : widgets.keySet()) {
            String selecting = entry;
            ElementHandler element = new TabButtonElement(
                            ButtonAction.run(() -> this.setGlobal(selecting)),
                            TextProvider.of(Text.translatableWithFallback(
                                    "widget.click-gui.selection." + selecting, selecting)),
                            () -> ClickGui.INSTANCE.textColor.get().withAlpha(255),
                            () -> ClickGui.INSTANCE.moduleListColor.get().withAlpha(255),
                            () -> Objects.equals(this.selecting, selecting))
                    .withTooltips(TooltipHandler.of(ChatUtils.parseTooltipsTranslation(
                            "widget.click-gui.selection." + selecting + ".tooltips", "暂无介绍")));
            ExecutableWidget.instance(blank + cnt * width, 0, width, BUTTON_HEIGHT)
                    .setElementHandler(element)
                    .addTo(this);
            cnt += 1;
        }
        // resize
        String currentSelect = selecting;
        selecting = null;
        setGlobal(currentSelect);
        widgetDelegate = new ContentDelegateWidget<>(0, BUTTON_HEIGHT, this.width, this.height - BUTTON_HEIGHT)
                .setContentDelegate(widget)
                .addTo(this);
    }
}

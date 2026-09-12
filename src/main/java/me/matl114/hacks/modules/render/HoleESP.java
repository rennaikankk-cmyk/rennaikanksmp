package me.matl114.hacks.modules.render;

import java.util.ArrayList;
import java.util.List;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ColorUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * Highlights 1x1 blast-proof holes (bedrock / obsidian walls) around the
 * player — the classic crystal-PvP positional overlay. Pure bedrock holes
 * get their own color since they cannot be broken out of.
 */
public class HoleESP extends BaseModule {
    public HoleESP() {
        super("HoleESP");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.RENDER_CONFIG, "render.hole-esp");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            toggleHotkey(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final IntRef distance = intBuilder(root.add("distance"))
            .defaultValue(6)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef rescanTicks = intBuilder(root.add("rescan-ticks"))
            .defaultValue(20)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef verticalRange = intBuilder(root.add("vertical-range"))
            .defaultValue(4)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final NBTRef<WrapColor> bedrockColor = builder(root.add("bedrock-color"), WrapColor.class)
            .defaultValue(new WrapColor(Formatting.GREEN))
            .build();

    public final NBTRef<WrapColor> obsidianColor = builder(root.add("obsidian-color"), WrapColor.class)
            .defaultValue(new WrapColor(Formatting.YELLOW))
            .build();

    private record Hole(Box box, boolean bedrockOnly) {}

    private final List<Hole> holes = new ArrayList<>();
    private int nextScanTick = 0;
    private final RenderCollector<Box> boxCollector = RenderCollectors.createBoxCollector(true, true, false);

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::onTick);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
    }

    public void onTick(Event<ClientPlayerEntity> event) {
        if (checkNull() || !enable.get()) {
            if (!holes.isEmpty()) {
                holes.clear();
                boxCollector.clear();
            }
            return;
        }
        int tick = Tasks.getTick();
        if (tick < nextScanTick) {
            return;
        }
        nextScanTick = tick + rescanTicks.get();
        rescan();
        boxCollector.clear();
        int bedrockRgb = bedrockColor.get().color().getRgb();
        int obsidianRgb = obsidianColor.get().color().getRgb();
        for (Hole hole : holes) {
            int color = hole.bedrockOnly() ? bedrockRgb : obsidianRgb;
            boxCollector.submit(hole.box(), ColorUtils.withAlphaInt(color, 72));
        }
    }

    private void rescan() {
        holes.clear();
        BlockPos center = mc.player.getBlockPos();
        int radius = distance.get();
        int vertical = verticalRange.get();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -vertical; dy <= vertical; dy++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    Boolean bedrockOnly = classifyHole(pos);
                    if (bedrockOnly != null) {
                        Box box = new Box(pos);
                        holes.add(new Hole(box, bedrockOnly));
                    }
                }
            }
        }
    }

    /** returns null when not a hole, otherwise whether all four walls are bedrock */
    private Boolean classifyHole(BlockPos pos) {
        BlockState floor = mc.world.getBlockState(pos.down());
        if (!floor.isOpaqueFullCube(mc.world, pos.down())) {
            return null;
        }
        if (!mc.world.getBlockState(pos).isAir()
                || !mc.world.getBlockState(pos.up()).isAir()) {
            return null;
        }
        boolean allBedrock = true;
        for (Direction direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockState wall = mc.world.getBlockState(pos.offset(direction));
            if (wall.isOf(Blocks.BEDROCK)) {
                continue;
            }
            if (wall.isOf(Blocks.OBSIDIAN)) {
                allBedrock = false;
                continue;
            }
            return null;
        }
        return allBedrock;
    }

    public void onRender(Event<MatrixStack> event) {
        if (checkNull() || !enable.get() || holes.isEmpty()) {
            return;
        }
        MatrixStack stack = event.context();
        RenderUtils.startDrawVirtual(stack);
        try {
            boxCollector.render3D(stack);
        } finally {
            RenderUtils.stopDrawVirtual(stack);
        }
    }
}

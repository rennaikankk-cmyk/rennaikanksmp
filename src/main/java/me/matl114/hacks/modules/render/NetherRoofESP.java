package me.matl114.hacks.modules.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.render.RenderElements;
import me.matl114.managers.Configs;
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
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Nether roof base finder: above the bedrock ceiling (y >= logicalHeight) no
 * block generates naturally, so ANY block found there is man-made — a base
 * floor, an ice highway, rails, or a portal frame. Chunks entering render
 * distance are scanned (budgeted per tick, empty sections skipped via the
 * palette container) and every hit is rendered with a marker box, tracer and
 * a label that flags portals and chests.
 */
public class NetherRoofESP extends BaseModule {
    public NetherRoofESP() {
        super("NetherRoofESP");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.RENDER_CONFIG, "render.nether-roof-esp");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            toggleHotkey(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    /** how many blocks above the ceiling to scan */
    public final IntRef scanHeight = intBuilder(root.add("scan-height"))
            .defaultValue(48)
            .validator(Configs.INT_POSITIVE)
            .build();

    /** chunk scan budget per tick, keeps the scan invisible on the frame time */
    public final IntRef chunksPerTick = intBuilder(root.add("chunks-per-tick"))
            .defaultValue(4)
            .validator(Configs.INT_POSITIVE)
            .build();

    /** minimum man-made blocks before a chunk counts as a finding */
    public final IntRef minBlocks = intBuilder(root.add("min-blocks"))
            .defaultValue(1)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final NBTRef<WrapColor> color = builder(root.add("color"), WrapColor.class)
            .defaultValue(new WrapColor(Formatting.AQUA))
            .build();

    public final FlagRef showTracers =
            builder(root.add("show-tracers"), Boolean.class).defaultValue(true).build();

    private record Finding(Vec3d center, int blockCount, boolean portal, boolean chest) {}

    private final Map<Long, Finding> findings = new HashMap<>();
    private final Set<Long> scannedChunks = new HashSet<>();
    private net.minecraft.registry.RegistryKey<net.minecraft.world.World> currentDimension;

    private final RenderCollector<Box> boxCollector = RenderCollectors.createBoxCollector(true, true, false);
    private final RenderCollector<RenderElements.Text> textCollector = RenderCollectors.createTextCollector();
    private final RenderCollector<Vec3d> tracerCollector = RenderCollectors.createTracerCollector();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::onTick);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
    }

    public void onTick(Event<ClientPlayerEntity> event) {
        if (checkNull() || !enable.get() || !mc.world.getDimension().hasCeiling()) {
            resetState();
            return;
        }
        net.minecraft.registry.RegistryKey<net.minecraft.world.World> dimension = mc.world.getRegistryKey();
        if (dimension != currentDimension) {
            resetState();
            currentDimension = dimension;
        }
        scanNewChunks();
    }

    private void resetState() {
        if (!findings.isEmpty() || !scannedChunks.isEmpty()) {
            findings.clear();
            scannedChunks.clear();
            boxCollector.clear();
            textCollector.clear();
            tracerCollector.clear();
        }
        currentDimension = null;
    }

    private void scanNewChunks() {
        int viewDistance = mc.options.getViewDistance().getValue();
        ChunkPos playerChunk = mc.player.getChunkPos();
        int budget = chunksPerTick.get();
        // spiral out from the player's chunk so nearby bases light up first
        outer:
        for (int radius = 0; radius <= viewDistance; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int cx = playerChunk.x + dx;
                    int cz = playerChunk.z + dz;
                    long key = ChunkPos.toLong(cx, cz);
                    if (scannedChunks.contains(key)) {
                        continue;
                    }
                    if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) {
                        continue;
                    }
                    WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    scanChunk(chunk, key);
                    if (--budget <= 0) {
                        break outer;
                    }
                }
            }
        }
    }

    /** records the chunk into findings when it holds enough man-made blocks */
    private void scanChunk(WorldChunk chunk, long key) {
        scannedChunks.add(key);
        int ceilingY = mc.world.getDimension().logicalHeight();
        int worldBottom = mc.world.getDimension().minY();
        ChunkSection[] sections = chunk.getSectionArray();
        int startIdx = Math.max(0, (ceilingY - worldBottom) >> 4);
        int endY = Math.min(ceilingY + scanHeight.get(), worldBottom + (sections.length << 4));
        int endIdx = Math.min(sections.length, ((endY - worldBottom) >> 4) + 1);
        int count = 0;
        long sumX = 0, sumY = 0, sumZ = 0;
        boolean portal = false;
        boolean chest = false;
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        for (int idx = startIdx; idx < endIdx; idx++) {
            ChunkSection section = sections[idx];
            if (section == null || section.isEmpty()) {
                continue;
            }
            int baseY = worldBottom + (idx << 4);
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) {
                            continue;
                        }
                        count++;
                        sumX += baseX + lx;
                        sumY += baseY + ly;
                        sumZ += baseZ + lz;
                        if (state.isOf(Blocks.NETHER_PORTAL)) {
                            portal = true;
                        } else if (state.isOf(Blocks.CHEST)
                                || state.isOf(Blocks.TRAPPED_CHEST)
                                || state.isOf(Blocks.ENDER_CHEST)) {
                            chest = true;
                        }
                    }
                }
            }
        }
        if (count < minBlocks.get()) {
            return;
        }
        Vec3d center = new Vec3d((double) sumX / count, (double) sumY / count, (double) sumZ / count);
        findings.put(key, new Finding(center, count, portal, chest));
        rebuildCollectors();
    }

    private void rebuildCollectors() {
        boxCollector.clear();
        textCollector.clear();
        tracerCollector.clear();
        int rgb = color.get().color().getRgb();
        for (Finding finding : findings.values()) {
            Vec3d center = finding.center();
            boxCollector.submit(
                    new Box(
                            center.x - 2.0D,
                            center.y - 2.0D,
                            center.z - 2.0D,
                            center.x + 2.0D,
                            center.y + 2.0D,
                            center.z + 2.0D),
                    ColorUtils.withAlphaInt(rgb, 60));
            if (showTracers.get()) {
                tracerCollector.submit(center, ColorUtils.withAlphaInt(rgb, 200));
            }
            String flags = (finding.portal() ? " 传送门" : "") + (finding.chest() ? " 箱子" : "");
            textCollector.submit(
                    new RenderElements.Text(
                            Text.literal("%d格%s".formatted(finding.blockCount(), flags)), center.add(0, 2.5D, 0), 1.0F),
                    ColorUtils.withAlphaInt(rgb, 255));
        }
    }

    public void onRender(Event<MatrixStack> event) {
        if (checkNull() || !enable.get() || findings.isEmpty()) {
            return;
        }
        MatrixStack stack = event.context();
        RenderUtils.startDrawVirtual(stack);
        try {
            boxCollector.render3D(stack);
            tracerCollector.render3D(stack);
            textCollector.render3D(stack);
        } finally {
            RenderUtils.stopDrawVirtual(stack);
        }
    }
}

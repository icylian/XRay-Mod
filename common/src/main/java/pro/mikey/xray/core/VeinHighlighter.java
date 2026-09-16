package pro.mikey.xray.core;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clusters rendered targets into veins (6-connected adjacency) and picks the ones worth
 * highlighting: at least {@link #MIN_VEIN_SIZE} blocks, inside one of the top-two densest
 * directions, closest to the player. Runs on a budget (called at most twice a second) and
 * only on the render thread.
 */
public final class VeinHighlighter {
    public static final int MIN_VEIN_SIZE = 3;
    // Vertical distance is squared twice (weight = sqrt(4) = 2): one block of height
    // counts like two blocks of walking distance when ranking veins
    private static final int Y_WEIGHT_SQ = 4;
    private static final long CACHE_MS = 250;

    private Set<Long> highlightedVeinBlocks = Set.of();
    private long lastRunMs = 0;
    // Root of the currently highlighted vein for stickiness (Long.MIN_VALUE = none);
    // reset whenever the vein disappears or XRay is force-refreshed
    private long currentVeinRoot = Long.MIN_VALUE;

    /**
     * Forget the current vein pick — used when the world/render list is force-refreshed,
     * so a stale root can never pin a highlight that no longer exists.
     */
    public synchronized void reset() {
        currentVeinRoot = Long.MIN_VALUE;
        highlightedVeinBlocks = Set.of();
        lastRunMs = 0;
    }

    /**
     * True when cave avoidance is enabled in the config.
     */
    private static boolean caveAvoidanceEnabled() {
        return pro.mikey.xray.XRay.config().caveAvoidance.get();
    }

    public record VeinResult(Set<Long> highlightedBlocks, int veinCount) {}

    /**
     * Returns the block positions (packed longs) belonging to highlight-worthy veins.
     * A vein qualifies when it has >= MIN_VEIN_SIZE blocks and sits in one of the top-two
     * densest directions; among qualifying veins the closest one wins, and any ties in
     * distance are all highlighted.
     */
    public synchronized Set<Long> getHighlightedBlocks() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            return Set.of();
        }

        long now = System.currentTimeMillis();
        if (now - lastRunMs < CACHE_MS) {
            return highlightedVeinBlocks;
        }
        lastRunMs = now;

        // Snapshot the render list without holding the map monitor for the whole analysis
        List<long[]> chunkSnapshots = new ArrayList<>();
        synchronized (ScanController.INSTANCE.syncRenderList) {
            for (var entry : ScanController.INSTANCE.syncRenderList.entrySet()) {
                long[] packed = new long[entry.getValue().size()];
                int i = 0;
                for (OutlineRenderTarget t : entry.getValue()) {
                    packed[i++] = BlockPos.asLong(t.x(), t.y(), t.z());
                }
                chunkSnapshots.add(packed);
            }
        }

        var density = ScanController.INSTANCE.getDensityCounts();
        Set<String> topDirections = topTwoDirections(density);

        // Union-find over all target positions, 6-connected
        Map<Long, Long> parent = new HashMap<>();
        for (long[] chunk : chunkSnapshots) {
            for (long pos : chunk) {
                parent.put(pos, pos);
            }
        }

        for (long[] chunk : chunkSnapshots) {
            for (long pos : chunk) {
                for (long neighbor : neighbors(pos)) {
                    if (parent.containsKey(neighbor)) {
                        union(parent, pos, neighbor);
                    }
                }
            }
        }

        // Group positions into veins and compute size + nearest distance per vein
        Map<Long, List<Long>> veins = new HashMap<>();
        Map<Long, Integer> veinSizes = new HashMap<>();
        Map<Long, Integer> veinNearestDist = new HashMap<>();

        var playerPos = player.blockPosition();
        for (long pos : parent.keySet()) {
            long root = find(parent, pos);
            veins.computeIfAbsent(root, k -> new ArrayList<>()).add(pos);
            veinSizes.merge(root, 1, Integer::sum);

            BlockPos p = BlockPos.of(pos);
            int dx = p.getX() - playerPos.getX();
            int dy = p.getY() - playerPos.getY();
            int dz = p.getZ() - playerPos.getZ();
            // Vertical distance is weighted down: mining up/down a block is much slower
            // than walking a block horizontally (stairs, ladders, digging at an angle),
            // so a vein a few blocks above the head should not outrank one at foot level
            // a similar walking distance away.
            int weightedYSq = dy * dy * Y_WEIGHT_SQ;
            int distSq = dx * dx + dz * dz + weightedYSq;
            veinNearestDist.merge(root, distSq, Math::min);
        }

        // Pick the closest vein among those big enough and inside a top-density direction
        long bestRoot = Long.MIN_VALUE;
        int bestDist = Integer.MAX_VALUE;

        // Currently highlighted vein, if it still exists in this analysis
        long currentRoot = Long.MIN_VALUE;
        int currentDist = Integer.MAX_VALUE;

        var level = player.level();
        boolean avoidCaves = caveAvoidanceEnabled();

        for (var veinEntry : veins.entrySet()) {
            long root = veinEntry.getKey();
            if (veinSizes.get(root) < MIN_VEIN_SIZE) {
                continue;
            }

            if (!veinInTopDirection(veinEntry.getValue(), playerPos, topDirections)) {
                continue;
            }

            // Skip veins sitting on top of a cave when avoidance is on: their dig spots
            // drop into air, which either makes the player fall or leaves ore overhead
            if (avoidCaves && veinOverCave(veinEntry.getValue(), level)) {
                continue;
            }

            int dist = veinNearestDist.get(root);
            if (root == currentVeinRoot) {
                currentRoot = root;
                currentDist = dist;
            }
            if (dist < bestDist) {
                bestDist = dist;
                bestRoot = root;
            }
        }

        // Stickiness: keep highlighting the vein the player is already walking toward
        // unless it vanished (mined out / out of range / disqualified), or a competitor is
        // dramatically closer (40%+). Without this, tiny rescan jitter steals the target
        // mid-dig and the highlight flickers between veins.
        if (currentRoot != Long.MIN_VALUE
                && bestRoot != currentRoot
                && bestDist > currentDist * 3 / 5) {
            bestRoot = currentRoot;
            bestDist = currentDist;
        }

        if (bestRoot == Long.MIN_VALUE) {
            highlightedVeinBlocks = Set.of();
            currentVeinRoot = Long.MIN_VALUE;
        } else {
            highlightedVeinBlocks = new HashSet<>(veins.get(bestRoot));
            currentVeinRoot = bestRoot;
        }

        return highlightedVeinBlocks;
    }

    /** The two direction names (N/E/S/W) with the highest target counts. */
    private static Set<String> topTwoDirections(ScanController.DensityCounts density) {
        record Dir(String name, int count) {}
        List<Dir> dirs = List.of(
            new Dir("N", density.north()),
            new Dir("E", density.east()),
            new Dir("S", density.south()),
            new Dir("W", density.west())
        );

        return dirs.stream()
            .sorted((a, b) -> Integer.compare(b.count(), a.count()))
            .limit(2)
            .filter(d -> d.count() > 0)
            .map(Dir::name)
            .collect(java.util.stream.Collectors.toSet());
    }

    /** A vein counts as "in a direction" when its horizontal center of mass does. */
    private static boolean veinInTopDirection(List<Long> blocks, BlockPos playerPos, Set<String> topDirections) {
        long sumX = 0, sumZ = 0;
        for (long pos : blocks) {
            BlockPos p = BlockPos.of(pos);
            sumX += p.getX();
            sumZ += p.getZ();
        }

        int dx = (int) (sumX / blocks.size()) - playerPos.getX();
        int dz = (int) (sumZ / blocks.size()) - playerPos.getZ();

        String direction;
        if (Math.abs(dz) >= Math.abs(dx)) {
            direction = dz < 0 ? "N" : "S";
        } else {
            direction = dx > 0 ? "E" : "W";
        }

        return topDirections.contains(direction);
    }

    /**
     * True when any dig spot of the vein (checked on the vein's top layer) stands on air
     * within the configured check depth — i.e. the vein hangs over a cave and mining it
     * would drop the player or put ore overhead.
     */
    private static boolean veinOverCave(List<Long> blocks, net.minecraft.world.level.Level level) {
        int checkDepth = Math.max(1, pro.mikey.xray.XRay.config().caveCheckDepth.get());
        int minAir = Math.max(1, pro.mikey.xray.XRay.config().caveCheckMinAir.get());

        // Collect the vein's top layer: highest Y of the vein, and all blocks at that Y
        int topY = Integer.MIN_VALUE;
        for (long pos : blocks) {
            topY = Math.max(topY, BlockPos.of(pos).getY());
        }

        for (long pos : blocks) {
            BlockPos p = BlockPos.of(pos);
            if (p.getY() != topY) {
                continue;
            }

            // Check the column under each top-layer block: if it opens into air within
            // the check depth, the vein is a cave ceiling
            int airRun = 0;
            for (int dy = 1; dy <= checkDepth; dy++) {
                BlockPos below = p.below(dy);
                if (isAirOrFluid(level, below)) {
                    airRun++;
                    if (airRun >= minAir) {
                        return true; // open space right under the vein -> cave ceiling
                    }
                } else {
                    airRun = 0;
                }
            }
        }

        return false;
    }

    private static boolean isAirOrFluid(net.minecraft.world.level.Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    private static long[] neighbors(long pos) {
        BlockPos p = BlockPos.of(pos);
        return new long[] {
            BlockPos.asLong(p.getX() + 1, p.getY(), p.getZ()),
            BlockPos.asLong(p.getX() - 1, p.getY(), p.getZ()),
            BlockPos.asLong(p.getX(), p.getY() + 1, p.getZ()),
            BlockPos.asLong(p.getX(), p.getY() - 1, p.getZ()),
            BlockPos.asLong(p.getX(), p.getY(), p.getZ() + 1),
            BlockPos.asLong(p.getX(), p.getY(), p.getZ() - 1),
        };
    }

    private static long find(Map<Long, Long> parent, long node) {
        long root = node;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        // Path compression
        while (parent.get(node) != root) {
            long next = parent.get(node);
            parent.put(node, root);
            node = next;
        }
        return root;
    }

    private static void union(Map<Long, Long> parent, long a, long b) {
        long ra = find(parent, a);
        long rb = find(parent, b);
        if (ra != rb) {
            parent.put(ra, rb);
        }
    }
}

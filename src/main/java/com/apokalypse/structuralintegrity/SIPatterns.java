package com.apokalypse.structuralintegrity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Wildcard integrity overrides from the {@code integrityPatterns} config list.
 *
 * Data maps only take exact ids or tags, so pattern selection lives config-side:
 * entries like {@code create:*_casing=40} match against the block's full registry
 * id, {@code *} standing for any run of characters. Sits between the datamap and
 * the hardness derivation in {@link Integrity#naturalOf}: a datamap row always
 * wins, a pattern beats derivation. First matching entry in list order wins.
 *
 * Resolution is cached per block; a config file save fires a reload event and
 * {@link #invalidate()} drops both the compiled list and the cache, so edits
 * apply live without a restart.
 */
final class SIPatterns {
    private SIPatterns() {}

    /** Cache sentinel - the block matched no pattern. */
    static final int NO_MATCH = -1;

    private record Rule(Pattern pattern, int integrity) {}

    private static volatile List<Rule> compiled;
    private static final Map<Block, Integer> CACHE = new ConcurrentHashMap<>();

    /** The pattern integrity for this block, or {@link #NO_MATCH}. */
    static int lookup(Block block) {
        List<Rule> rules = compiled;
        if (rules == null) {
            rules = compile();
            compiled = rules;
        }
        if (rules.isEmpty()) {
            return NO_MATCH;
        }
        final List<Rule> current = rules;
        return CACHE.computeIfAbsent(block, b -> resolve(current, b));
    }

    /** Config changed - recompile on next lookup and re-resolve every block. */
    static void invalidate() {
        compiled = null;
        CACHE.clear();
    }

    private static int resolve(List<Rule> rules, Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        String id = key.toString();
        for (Rule rule : rules) {
            if (rule.pattern.matcher(id).matches()) {
                return rule.integrity;
            }
        }
        return NO_MATCH;
    }

    private static List<Rule> compile() {
        List<Rule> out = new ArrayList<>();
        for (String entry : SIConfig.integrityPatterns()) {
            int eq = entry.lastIndexOf('=');
            if (eq <= 0) {
                continue; // the config validator already rejects these; belt and braces
            }
            String glob = entry.substring(0, eq).trim();
            int integrity;
            try {
                integrity = Integer.parseInt(entry.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            out.add(new Rule(globToRegex(glob), Math.min(1024, Math.max(1, integrity))));
        }
        if (!out.isEmpty()) {
            StructuralIntegrity.LOGGER.info("[SI] {} integrity pattern(s) compiled", out.size());
        }
        return out;
    }

    /** {@code *} matches any run of characters; everything else is literal. */
    private static Pattern globToRegex(String glob) {
        StringBuilder rx = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (rx.length() > 0) {
                rx.append(".*");
            }
            rx.append(Pattern.quote(part));
        }
        return Pattern.compile(rx.toString());
    }
}

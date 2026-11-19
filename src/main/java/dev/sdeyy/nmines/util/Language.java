package dev.sdeyy.nmines.util;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Immutable language snapshots, safe for asynchronous placeholder readers. */
public final class Language {
    private static final Map<String, String> DEFAULTS = defaults();
    private static volatile Map<String, String> active = DEFAULTS;
    private static final java.util.regex.Pattern ARGUMENT = java.util.regex.Pattern.compile("\\{([0-9]+)\\}");
    private Language() { }
    private static Map<String, String> defaults() {
        try (InputStream input = Language.class.getResourceAsStream("/language.yml")) {
            if (input == null) throw new IllegalStateException("Missing bundled language.yml");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            Map<String, String> result = new LinkedHashMap<>();
            for (String key : yaml.getKeys(true)) if (!yaml.isConfigurationSection(key)) {
                if (!(yaml.get(key) instanceof String)) throw new IllegalStateException("Language values must be strings: " + key);
                result.put(key, yaml.getString(key));
            }
            return Collections.unmodifiableMap(result);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    public static Map<String, String> read(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
        Map<String, String> result = new LinkedHashMap<>(DEFAULTS);
        for (String key : yaml.getKeys(true)) if (!yaml.isConfigurationSection(key)) {
            if (!(yaml.get(key) instanceof String)) throw new IllegalArgumentException("Language values must be strings: " + key);
            result.put(key, yaml.getString(key));
        }
        // Reject a scalar that hides a whole group of known messages.
        for (String key : DEFAULTS.keySet()) {
            String parent = key;
            while (parent.contains(".")) {
                parent = parent.substring(0, parent.lastIndexOf('.'));
                if (yaml.contains(parent) && !yaml.isConfigurationSection(parent)) throw new IllegalArgumentException("Language group must be a section: " + parent);
            }
        }
        String word = result.get("input.cancel-word");
        if (word == null || word.trim().isEmpty() || word.contains("\n")) throw new IllegalArgumentException("input.cancel-word must not be blank");
        return Collections.unmodifiableMap(result);
    }
    public static void load(JavaPlugin plugin) throws Exception {
        File file = new File(plugin.getDataFolder(), "language.yml");
        if (!file.exists()) plugin.saveResource("language.yml", false);
        install(read(file.toPath()));
    }
    public static void install(Map<String, String> snapshot) { active = Collections.unmodifiableMap(new LinkedHashMap<>(snapshot)); }
    public static String text(String key, Object... arguments) {
        String value = active.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
        if (arguments.length == 0) return value;
        java.util.regex.Matcher matcher = ARGUMENT.matcher(value);
        StringBuffer formatted = new StringBuffer();
        while (matcher.find()) {
            int index;
            try { index = Integer.parseInt(matcher.group(1)); } catch (NumberFormatException e) { continue; }
            if (index < arguments.length) matcher.appendReplacement(formatted, java.util.regex.Matcher.quoteReplacement(String.valueOf(arguments[index])));
        }
        matcher.appendTail(formatted); return formatted.toString();
    }
    public static String error(Throwable error) {
        if (error instanceof NumberFormatException) return text("errors.invalid-number");
        if (error instanceof IllegalArgumentException && error.getMessage() != null && error.getMessage().startsWith("No enum constant ")) return text("errors.invalid-value");
        return error.getMessage() == null ? text("errors.invalid-value") : error.getMessage();
    }
}

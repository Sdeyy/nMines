package dev.sdeyy.nmines;

import dev.sdeyy.nmines.command.MineCommand;
import dev.sdeyy.nmines.gui.MenuService;
import dev.sdeyy.nmines.gui.PromptService;
import dev.sdeyy.nmines.integration.MinePlaceholders;
import dev.sdeyy.nmines.listener.MineListener;
import dev.sdeyy.nmines.service.MineService;
import dev.sdeyy.nmines.service.MineValidator;
import dev.sdeyy.nmines.service.SelectionService;
import dev.sdeyy.nmines.service.Settings;
import dev.sdeyy.nmines.util.Language;
import dev.sdeyy.nmines.util.Text;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class NMinesPlugin extends JavaPlugin {
    private MineService mines;
    private MenuService menus;
    private PromptService prompts;
    private MineCommand commands;
    private MinePlaceholders placeholders;
    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            Language.load(this);
            YamlConfiguration settings = new YamlConfiguration(); settings.load(getDataFolder().toPath().resolve("config.yml").toFile()); Settings.validate(settings);
            Files.createDirectories(getDataFolder().toPath().resolve("schematics"));
            Text text = new Text(this);
            mines = new MineService(this, text); prompts = new PromptService(this, text);
            SelectionService selections = new SelectionService(this, text);
            menus = new MenuService(this, mines, text, prompts, selections);
            commands = new MineCommand(this, mines, text, selections, menus);
            PluginCommand command = getCommand("nmines");
            if (command == null) throw new IllegalStateException(Language.text("startup.missing-nmines-command-in-plugin-yml"));
            command.setExecutor(commands); command.setTabCompleter(commands);
            Bukkit.getPluginManager().registerEvents(selections, this);
            Bukkit.getPluginManager().registerEvents(prompts, this);
            Bukkit.getPluginManager().registerEvents(menus, this);
            Bukkit.getPluginManager().registerEvents(new MineListener(this, mines, text), this);
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                placeholders = new MinePlaceholders(mines); placeholders.register();
            }
            mines.start(); menus.start();
            getLogger().info(Language.text("startup.nmines-enabled-on-format", Bukkit.getBukkitVersion(), mines.all().size()));
        } catch (Exception e) {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            // Include the trace in the message itself, even if a logging formatter ignores LogRecord.getThrown().
            getLogger().severe(Language.text("startup.could-not-start-nmines-existing-data-was-not-overwritten")
                    + System.lineSeparator() + trace.toString());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    public void reload() throws Exception {
        mines.flushSaves();
        YamlConfiguration candidate = new YamlConfiguration(); candidate.load(getDataFolder().toPath().resolve("config.yml").toFile());
        java.util.Map<String, String> nextLanguage = Language.read(getDataFolder().toPath().resolve("language.yml"));
        Settings.validate(candidate);
        // Validate the new limits and on-disk definitions before touching live state.
        new MineValidator(candidate).validate(new dev.sdeyy.nmines.storage.MineRepository(getDataFolder().toPath().resolve("mines.yml")).load());
        String oldConfig = getConfig().saveToString();
        try { reloadConfig(); mines.reload(); }
        catch (Exception e) {
            try { getConfig().loadFromString(oldConfig); }
            catch (Exception restoreError) { e.addSuppressed(restoreError); }
            throw e;
        }
        Language.install(nextLanguage);
        prompts.clear(); commands.clear(); menus.stop(); menus.start();
    }
    public PromptService prompts() { return prompts; }
    @Override public void onDisable() {
        if (menus != null) menus.stop(); if (prompts != null) prompts.clear();
        if (commands != null) commands.clear(); if (mines != null) mines.stop();
        if (placeholders != null) placeholders.unregister();
    }
}

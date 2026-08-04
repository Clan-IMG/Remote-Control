package net.clanimg.remote_control.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;

public class RemoteControlConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("remote_control.json");

    public String apiUrl = "http://localhost:8000";
    public String apiToken = "";

    private static RemoteControlConfig instance;

    public static RemoteControlConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static RemoteControlConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = new FileReader(CONFIG_PATH.toFile())) {
                return GSON.fromJson(reader, RemoteControlConfig.class);
            } catch (IOException e) {
                LOGGER.error("Failed to load config, using defaults", e);
            }
        }
        RemoteControlConfig config = new RemoteControlConfig();
        config.save();
        return config;
    }

    public void save() {
        try (Writer writer = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }
}

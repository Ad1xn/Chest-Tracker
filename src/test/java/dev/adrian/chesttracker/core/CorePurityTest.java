package dev.adrian.chesttracker.core;

import dev.adrian.chesttracker.core.index.WorldIndex;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the property the whole architecture rests on: {@code core} is the
 * engine, and it must not depend on Minecraft or Bukkit.
 *
 * <p>Keeping it pure is what lets the same code back the Fabric mod on two very
 * different Minecraft versions and, later, a Paper plugin - and what lets every
 * test in this package run without a game. Extracting it into its own Gradle
 * module then becomes a file move rather than an untangling job.
 *
 * <p>Works on compiled bytecode rather than source paths, so it does not care
 * where the build put things: class-file constant pools store referenced type
 * names as plain UTF-8, so a forbidden import is visible as a literal substring.
 */
class CorePurityTest {

    private static final List<String> FORBIDDEN = List.of("net/minecraft", "org/bukkit", "net/fabricmc");

    @Test
    void coreDoesNotReferenceMinecraftOrBukkit() throws IOException, URISyntaxException {
        Path coreRoot = locateCoreClasses();
        assertTrue(Files.isDirectory(coreRoot), "could not locate compiled core classes at " + coreRoot);

        List<String> violations = new ArrayList<>();
        try (Stream<Path> classes = Files.walk(coreRoot)) {
            for (Path classFile : classes.filter(p -> p.toString().endsWith(".class")).toList()) {
                String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                for (String forbidden : FORBIDDEN) {
                    if (bytes.contains(forbidden)) {
                        violations.add(coreRoot.relativize(classFile) + " references " + forbidden);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "core must stay free of game dependencies, but found:\n  " + String.join("\n  ", violations));
    }

    private static Path locateCoreClasses() throws URISyntaxException {
        Path marker = Path.of(WorldIndex.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return marker.resolve("dev/adrian/chesttracker/core");
    }
}

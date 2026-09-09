package com.biguzi.ragrevival;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.fml.loading.moddiscovery.NightConfigWrapper;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises FML's real dependency parser without starting Minecraft. */
class DependencyMetadataTest {
    @Test void unrestrictedDependenciesAcceptReportedAndBuildBaselineVersions() throws Exception {
        var dependencies = productionDependencies();
        var versions = Map.of(
                "neoforge", List.of("21.1.248", "21.1.249"),
                "sable", List.of("2.0.3", "2.0.5"));
        for (var entry : versions.entrySet()) {
            var dependency = required(dependencies, entry.getKey());
            assertSame(IModInfo.UNBOUNDED, dependency.getVersionRange(),
                    entry.getKey() + " must use FML's omitted-range default");
            for (String version : entry.getValue()) {
                assertTrue(accepts(dependency, version), entry.getKey() + " rejected " + version);
            }
        }
    }

    @Test void explicitlyEmptyRangeReproducesTheReleasedStartupFailure() throws Exception {
        // 1.3.3 supplied an empty string, which FML parses rather than applying its default.
        var versions = Map.of("neoforge", "21.1.248", "sable", "2.0.5");
        for (var entry : versions.entrySet()) {
            var config = new TomlParser().parse(new StringReader("""
                    modId="%s"
                    type="required"
                    versionRange=""
                    side="BOTH"
                    """.formatted(entry.getKey())));
            var dependency = parseDependency(config);
            assertFalse(accepts(dependency, entry.getValue()),
                    "The explicit-empty regression fixture must reproduce FML's rejection");
            assertTrue(accepts(required(productionDependencies(), entry.getKey()), entry.getValue()),
                    "The shipped metadata must fix the same rejected version");
        }
    }

    @Test void allRequiredModsRemainMandatoryOnBothSides() throws Exception {
        var dependencies = productionDependencies();
        for (String id : List.of("neoforge", "minecraft", "sable", "sable_player_ragdoll", "ragdoll_reactions")) {
            var dependency = required(dependencies, id);
            assertEquals(IModInfo.DependencyType.REQUIRED, dependency.getType(), id);
            assertEquals(IModInfo.DependencySide.BOTH, dependency.getSide(), id);
        }
        assertTrue(accepts(required(dependencies, "sable_player_ragdoll"), "0.7.2"));
        assertTrue(accepts(required(dependencies, "ragdoll_reactions"), "0.7.0"));
    }

    @Test void minecraftScopeRemainsExactlyOneTwentyOneOne() throws Exception {
        var minecraft = required(productionDependencies(), "minecraft");
        assertTrue(accepts(minecraft, "1.21.1"));
        assertFalse(accepts(minecraft, "1.21"));
        assertFalse(accepts(minecraft, "1.21.2"));
    }

    @Test void carryOnRemainsOptionalAndAcceptsTheIntegrationVersion() throws Exception {
        var carryOn = required(productionDependencies(), "carryon");
        assertEquals(IModInfo.DependencyType.OPTIONAL, carryOn.getType());
        assertEquals(IModInfo.DependencySide.BOTH, carryOn.getSide());
        assertTrue(accepts(carryOn, "2.2.6.13"));
    }

    private static Map<String, IModInfo.ModVersion> productionDependencies()
            throws IOException, ReflectiveOperationException {
        var stream = DependencyMetadataTest.class.getResourceAsStream("/META-INF/neoforge.mods.toml");
        assertNotNull(stream, "Production dependency metadata must be present in main resources");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var config = new TomlParser().parse(reader);
            List<? extends UnmodifiableConfig> entries = config.get("dependencies.ragrevival");
            assertNotNull(entries, "Loaded resource must be RagRevival's production metadata");
            Map<String, IModInfo.ModVersion> dependencies = new LinkedHashMap<>();
            for (var entry : entries) {
                var dependency = parseDependency(entry);
                assertNull(dependencies.put(dependency.getModId(), dependency), "Duplicate dependency declaration");
            }
            return dependencies;
        }
    }

    private static IModInfo.ModVersion parseDependency(UnmodifiableConfig config)
            throws ReflectiveOperationException {
        // This constructor is nonpublic. Its owning mod/file are unused for valid dependency
        // parsing, so no game bootstrap or synthetic reimplementation of range defaults is needed.
        var constructor = Class.forName("net.neoforged.fml.loading.moddiscovery.ModInfo$ModVersion")
                .getDeclaredConstructor(ModInfo.class, IModInfo.class, IConfigurable.class);
        constructor.setAccessible(true);
        return (IModInfo.ModVersion) constructor.newInstance(null, null, new NightConfigWrapper(config));
    }

    private static IModInfo.ModVersion required(Map<String, IModInfo.ModVersion> dependencies, String id) {
        var dependency = dependencies.get(id);
        assertNotNull(dependency, "Missing dependency declaration: " + id);
        return dependency;
    }

    private static boolean accepts(IModInfo.ModVersion dependency, String version) {
        return dependency.getVersionRange().containsVersion(new DefaultArtifactVersion(version));
    }
}

package net.msrandom.minecraftcodev.forge.mappings;

import com.google.common.base.Suppliers;
import cpw.mods.modlauncher.api.INameMappingService;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.fabricmc.mapping.tree.ClassDef;
import net.fabricmc.mapping.tree.FieldDef;
import net.fabricmc.mapping.tree.Mapped;
import net.fabricmc.mapping.tree.MethodDef;
import net.fabricmc.mapping.tree.TinyMappingFactory;

public class CodevNameMappingService implements INameMappingService {
    private final Supplier<Mappings> mappings = Suppliers.memoize(() -> {
        InputStream mappingsStream = getClass().getResourceAsStream("/mappings.zip");

        if (mappingsStream == null) {
            return new Mappings(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        }

        Map<String, String> classes = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();

        try (ZipInputStream zip = new ZipInputStream(mappingsStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().contains("mappings/mappings.tiny")) {
                    continue;
                }

                InputStream entryStream = new FilterInputStream(zip) {
                    @Override
                    public void close() throws IOException {
                        zip.closeEntry();
                    }
                };

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(entryStream))) {
                    for (ClassDef classDef : TinyMappingFactory.loadWithDetection(reader).getClasses()) {
                        addEntry(classes, classDef, true);

                        for (MethodDef method : classDef.getMethods()) {
                            addEntry(methods, method);
                        }

                        for (FieldDef field : classDef.getFields()) {
                            addEntry(fields, field);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new Mappings(classes, methods, fields);
    });

    private static void addEntry(Map<String, String> map, Mapped mapped) {
        addEntry(map, mapped, false);
    }

    private static void addEntry(Map<String, String> map, Mapped mapped, boolean isClass) {
        String srg = mapped.getName("srg");
        String named = mapped.getName("named");
        if (isClass) {
            map.put(processClassName(srg), processClassName(named));
        } else {
            map.put(srg, named);
        }

    }

    private static String processClassName(String name) {
        return name.replace('/', '.');
    }

    public String mappingName() {
        return "codev";
    }

    public String mappingVersion() {
        return "1";
    }

    public Map.Entry<String, String> understanding() {
        return new Map.Entry<String, String>() {
            @Override
            public String getKey() {
                return "srg";
            }

            @Override
            public String getValue() {
                return "mcp";
            }

            @Override
            public String setValue(String value) {
                throw new UnsupportedOperationException();
            }
        };
    }

    public BiFunction<INameMappingService.Domain, String, String> namingFunction() {
        return (domain, name) -> {
            switch (domain) {
                case CLASS:
                    return (String) ((Mappings) this.mappings.get()).classes.getOrDefault(name, name);
                case METHOD:
                    return (String) ((Mappings) this.mappings.get()).methods.getOrDefault(name, name);
                case FIELD:
                    return (String) ((Mappings) this.mappings.get()).fields.getOrDefault(name, name);
                default:
                    return name;
            }
        };
    }

    private static class Mappings {
        private final Map<String, String> classes;
        private final Map<String, String> methods;
        private final Map<String, String> fields;

        public Mappings(Map<String, String> classes, Map<String, String> methods, Map<String, String> fields) {
            this.classes = classes;
            this.methods = methods;
            this.fields = fields;
        }
    }
}


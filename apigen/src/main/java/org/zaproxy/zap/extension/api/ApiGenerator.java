/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2019 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.extension.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.stream.Collectors;
import org.parosproxy.paros.common.AbstractParam;

public class ApiGenerator {

    private static final String CONF_FILE = "apigen.properties";

    private static Path baseDir;

    private enum Generator {
        ALL(null, null),
        DOTNET(
                DotNetAPIGenerator.class,
                "zap-api-dotnet/src/OWASPZAPDotNetAPI/OWASPZAPDotNetAPI/Generated"),
        GO(GoAPIGenerator.class, "zap-api-go/zap/"),
        JAVA(
                JavaAPIGenerator.class,
                "zap-api-java/subprojects/zap-clientapi/src/main/java/org/zaproxy/clientapi/gen"),
        NODEJS(NodeJSAPIGenerator.class, "zap-api-nodejs/src/"),
        PHP(PhpAPIGenerator.class, "zaproxy/php/api/zapv2/src/Zap"),
        PYTHON(PythonAPIGenerator.class, "zap-api-python/src/zapv2/"),
        RUST(RustAPIGenerator.class, "zap-api-rust/src/");

        private final Class<? extends AbstractAPIGenerator> clazz;
        private final String outputDir;

        Generator(Class<? extends AbstractAPIGenerator> clazz, String outputDir) {
            this.clazz = clazz;
            this.outputDir = outputDir;
        }

        List<ApiGeneratorWrapper> createApiGenerators() {
            if (this == ALL) {
                EnumSet<Generator> generators = EnumSet.allOf(Generator.class);
                generators.remove(ALL);
                return generators.stream()
                        .map(Generator::createWrapper)
                        .collect(Collectors.toList());
            }

            return Collections.singletonList(createWrapper());
        }

        private ApiGeneratorWrapper createWrapper() {
            return new ApiGeneratorWrapper(clazz, baseDir.resolve(outputDir));
        }

        static Generator from(String value) {
            if (value == null || value.isEmpty()) {
                return ALL;
            }
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path confFile = Paths.get(CONF_FILE);
        if (Files.notExists(confFile)) {
            System.err.println("Configuration file does not exist: " + confFile);
            System.exit(1);
        }

        Properties conf = new Properties();
        try (InputStream in = Files.newInputStream(confFile)) {
            conf.load(in);
        }

        baseDir = getBaseDir(conf);

        String classNameApi = conf.getProperty("api");
        if (classNameApi == null || classNameApi.isEmpty()) {
            throw new IllegalArgumentException("The property api is null or empty.");
        }

        Class<ApiImplementor> classApiImplementor =
                (Class<ApiImplementor>) Class.forName(classNameApi);
        ApiImplementor api = classApiImplementor.getDeclaredConstructor().newInstance();

        String classNameOptions = conf.getProperty("options");
        if (classNameOptions != null && !classNameOptions.isEmpty()) {
            Class<AbstractParam> classAbstractParam =
                    (Class<AbstractParam>) Class.forName(classNameOptions);
            api.addApiOptions(classAbstractParam.getDeclaredConstructor().newInstance());
        }

        generate(Generator.from(conf.getProperty("language")).createApiGenerators(), api);
    }

    private static Path getBaseDir(Properties conf) {
        String path = conf.getProperty("basedir");
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("The property basedir is null or empty.");
        }

        Path dir = Paths.get(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException(
                    "The property basedir is not a directory or does not exist: " + path);
        }
        return dir;
    }

    private static void generate(List<ApiGeneratorWrapper> generators, ApiImplementor api) {
        ResourceBundle bundle =
                ResourceBundle.getBundle(
                        api.getClass().getPackage().getName() + ".resources.Messages",
                        Locale.ENGLISH,
                        api.getClass().getClassLoader(),
                        ResourceBundle.Control.getControl(
                                ResourceBundle.Control.FORMAT_PROPERTIES));

        generators.forEach(generator -> generator.generate(api, bundle));
    }

    private static class ApiGeneratorWrapper {

        private final Class<? extends AbstractAPIGenerator> clazz;
        private final String outputDir;

        public ApiGeneratorWrapper(Class<? extends AbstractAPIGenerator> clazz, Path outputDir) {
            this.clazz = clazz;
            this.outputDir = outputDir.toAbsolutePath().toString();
        }

        public void generate(ApiImplementor api, ResourceBundle bundle) {
            AbstractAPIGenerator generator;
            try {
                generator = createInstance(bundle);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            try {
                generator.generateAPIFiles(Arrays.asList(api));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private AbstractAPIGenerator createInstance(ResourceBundle bundle) throws Exception {
            try {
                return clazz.getDeclaredConstructor(
                                String.class, boolean.class, ResourceBundle.class)
                        .newInstance(outputDir, true, bundle);
            } catch (NoSuchMethodException e) {
                return clazz.getDeclaredConstructor(String.class, boolean.class)
                        .newInstance(outputDir, true);
            }
        }
    }
}

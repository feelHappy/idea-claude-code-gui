package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImpeccableHandlerTest {

    @Test
    public void shouldTreatModernImpeccableSkillAsReadyForCodex() throws Exception {
        Path projectRoot = Files.createTempDirectory("impeccable-handler");
        Path skillsDir = Files.createDirectories(projectRoot.resolve(".codex").resolve("skills"));
        createSkill(skillsDir, "impeccable");
        createSkill(skillsDir, "audit");
        createSkill(skillsDir, "critique");
        createSkill(skillsDir, "clarify");
        createSkill(skillsDir, "harden");
        createSkill(skillsDir, "polish");
        createSkill(skillsDir, "typeset");
        Files.writeString(
                skillsDir.resolve(".cc-ai-toolkit-impeccable.json"),
                "{\"installedVersion\":\"2.1.7\"}"
        );

        cacheLatestVersion("2.1.7");

        HandlerContext context = new HandlerContext(
                projectWithBasePath(projectRoot.toString()),
                null,
                null,
                null,
                new HandlerContext.JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                    }

                    @Override
                    public String escapeJs(String str) {
                        return str;
                    }
                }
        );
        ImpeccableHandler handler = new ImpeccableHandler(context);
        Object providerConfig = invokePrivate(handler, "resolveProvider", new Class<?>[]{String.class}, "{\"provider\":\"codex\"}");
        JsonObject status = (JsonObject) invokePrivate(
                handler,
                "buildStatus",
                new Class<?>[]{providerConfig.getClass()},
                providerConfig
        );

        assertEquals("ready", status.get("state").getAsString());
        assertTrue(status.get("installed").getAsBoolean());
        assertTrue(status.get("hasFrontendDesign").getAsBoolean());
        assertTrue(status.get("hasTeachCommand").getAsBoolean());
        assertEquals(7, status.get("skillCount").getAsInt());
    }

    private static void createSkill(Path skillsDir, String skillName) throws IOException {
        Path skillDir = Files.createDirectories(skillsDir.resolve(skillName));
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                """
                ---
                name: %s
                description: Test skill
                ---

                Test skill body.
                """.formatted(skillName)
        );
    }

    private static Project projectWithBasePath(String basePath) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getBasePath".equals(method.getName())) {
                return basePath;
            }

            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0F;
            }
            if (returnType == double.class) {
                return 0D;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return null;
        };
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                handler
        );
    }

    private static void cacheLatestVersion(String latestVersion) throws Exception {
        Field versionField = ImpeccableHandler.class.getDeclaredField("cachedLatestVersion");
        versionField.setAccessible(true);
        versionField.set(null, latestVersion);

        Field checkedAtField = ImpeccableHandler.class.getDeclaredField("cachedLatestVersionCheckedAt");
        checkedAtField.setAccessible(true);
        checkedAtField.setLong(null, System.currentTimeMillis());
    }

    private static Object invokePrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}

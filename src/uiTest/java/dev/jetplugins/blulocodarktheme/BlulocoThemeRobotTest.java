package dev.jetplugins.blulocodarktheme;

import com.intellij.remoterobot.RemoteRobot;
import com.intellij.remoterobot.data.RemoteComponent;
import com.intellij.remoterobot.fixtures.CommonContainerFixture;
import com.intellij.remoterobot.fixtures.ComponentFixture;
import com.intellij.remoterobot.fixtures.DefaultXpath;
import com.intellij.remoterobot.fixtures.FixtureName;
import com.intellij.remoterobot.fixtures.TextEditorFixture;
import com.intellij.remoterobot.utils.Keyboard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.assertj.swing.core.MouseButton;

import static com.intellij.remoterobot.search.locators.Locators.byXpath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end theme coverage against a real IntelliJ frame.
 *
 * <p>The test discovers every bundled theme, verifies live UI defaults, exercises real editor,
 * tool-window, completion, Settings and diff components, then captures Marketplace-ready images.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class BlulocoThemeRobotTest {
    private static final String ROBOT_URL = "http://127.0.0.1:8082";
    private static final int CAPTURE_WIDTH = 1200;
    private static final int CAPTURE_HEIGHT = 760;
    private static final List<String> SCENARIOS = List.of(
        "editor",
        "tool-windows",
        "completion",
        "settings",
        "diff"
    );

    private final RemoteRobot remoteRobot = new RemoteRobot(ROBOT_URL);
    private final Path screenshotDirectory = Path.of(
        System.getProperty("bluloco.screenshot.dir", "build/ui-test/screenshots")
    ).toAbsolutePath();

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void themesRenderInRealIntellijComponents() throws Exception {
        Files.createDirectories(screenshotDirectory);

        IdeFrameFixture frame = remoteRobot.find(IdeFrameFixture.class, Duration.ofMinutes(4));
        closeAuxiliaryWindows(frame);
        sizeFrame(frame);
        waitForProject(frame);
        openShowcaseFile(frame);
        assertSampleCode(frame);

        Map<String, ThemeDefinition> themes = loadThemes();

        Map<String, Map<String, BufferedImage>> captures = new LinkedHashMap<>();
        int themeIndex = 0;
        for (Map.Entry<String, ThemeDefinition> entry : themes.entrySet()) {
            themeIndex++;
            String slug = entry.getKey();
            ThemeDefinition theme = entry.getValue();
            closeAuxiliaryWindows(frame);
            setTheme(frame, theme);
            assertLiveThemeColors(theme);
            openShowcaseFile(frame);

            Map<String, BufferedImage> themeCaptures = new LinkedHashMap<>();
            captures.put(slug, themeCaptures);

            showEditorWorkspace(frame);
            themeCaptures.put("editor", capture(frame, slug, "editor"));
            assertColorCoverage(themeCaptures.get("editor"), theme.editorBackground(), 0.04);
            writeThemeShowcase(themeIndex, slug, theme, themeCaptures.get("editor"));

            showToolWindows(frame);
            themeCaptures.put("tool-windows", capture(frame, slug, "tool-windows"));

            showCompletion(frame);
            themeCaptures.put("completion", capture(frame, slug, "completion"));
            hideCompletion(frame);
            openShowcaseFile(frame);

            showSettings(frame, theme);
            themeCaptures.put("settings", capture(frame, slug, "settings"));
            closeAuxiliaryWindows(frame);

            showDiff(frame);
            themeCaptures.put("diff", capture(frame, slug, "diff"));
            closeAuxiliaryWindows(frame);
            closeSelectedDiffTab(frame);
        }

        List<String> slugs = List.copyOf(themes.keySet());
        for (int first = 0; first < slugs.size(); first++) {
            for (int second = first + 1; second < slugs.size(); second++) {
                String firstSlug = slugs.get(first);
                String secondSlug = slugs.get(second);
                assertTrue(
                    pixelDifferenceRatio(
                        captures.get(firstSlug).get("editor"),
                        captures.get(secondSlug).get("editor")
                    ) > 0.03,
                    firstSlug + " and " + secondSlug + " should render as visibly distinct themes"
                );
            }
        }
        if (captures.containsKey("dark") && captures.containsKey("midnight")) {
            assertTrue(
                averageLuminance(captures.get("midnight").get("editor"))
                    < averageLuminance(captures.get("dark").get("editor")),
                "Midnight should render darker than Dark"
            );
        }

        for (int index = 0; index < SCENARIOS.size(); index++) {
            String scenario = SCENARIOS.get(index);
            writeComparison(index + 1, scenario, themes, captures);
        }
    }

    @AfterAll
    void closeIdeWhenRequested() {
        if (!Boolean.getBoolean("bluloco.close.ide")) {
            return;
        }
        try {
            remoteRobot.runJs("""
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                    new java.lang.Runnable({run: function() {
                        com.intellij.openapi.application.ApplicationManager.getApplication().exit(true, true, false);
                    }})
                );
                """);
        } catch (RuntimeException ignored) {
            // The Robot connection can close before the HTTP response when the IDE exits.
        }
    }

    private void sizeFrame(IdeFrameFixture frame) {
        frame.runJs("""
            component.setBounds(0, 0, 1440, 900);
            component.validate();
            component.toFront();
            """, true);
        pause(Duration.ofSeconds(1));
    }

    private void waitForProject(IdeFrameFixture frame) {
        waitUntil("project and indices", Duration.ofMinutes(5), () -> Boolean.TRUE.equals(frame.callJs("""
            const helper = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component);
            if (helper == null || helper.getProject() == null) {
                false;
            } else {
                const project = helper.getProject();
                com.intellij.openapi.module.ModuleManager.getInstance(project).getModules().length > 0 &&
                    !com.intellij.openapi.project.DumbService.isDumb(project);
            }
            """, true)));
    }

    private void setTheme(IdeFrameFixture frame, ThemeDefinition theme) {
        String escapedName = escapeJs(theme.name());
        frame.runJs("""
            const manager = com.intellij.ide.ui.LafManager.getInstance();
            const installed = manager.getInstalledLookAndFeels();
            let selected = null;
            for (let index = 0; index < installed.length; index++) {
                if (String(installed[index].getName()) === '%s') {
                    selected = installed[index];
                    break;
                }
            }
            if (selected == null) {
                throw new Error('Theme is not installed: %s');
            }
            manager.setCurrentLookAndFeel(selected, false);
            manager.updateUI();

            const scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance()
                .getScheme('%s');
            if (scheme == null) {
                throw new Error('Editor scheme is not installed: %s');
            }
            com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().setGlobalScheme(scheme);
            """.formatted(escapedName, escapedName, escapedName, escapedName), true);

        waitUntil("look and feel " + theme.name(), Duration.ofSeconds(30), () ->
            theme.name().equals(currentLookAndFeelName())
        );
    }

    private String currentLookAndFeelName() {
        return remoteRobot.callJs("""
            String(com.intellij.ide.ui.LafManager.getInstance().getCurrentLookAndFeel().getName());
            """, true);
    }

    private Map<String, ThemeDefinition> loadThemes() throws IOException {
        String manifestProperty = System.getProperty("bluloco.theme.manifest");
        assertNotNull(manifestProperty, "Gradle must provide the generated screenshot theme manifest");
        Path manifest = Path.of(manifestProperty);
        assertTrue(Files.isRegularFile(manifest), "missing screenshot theme manifest: " + manifest);

        Map<String, ThemeDefinition> themes = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest)) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            assertEquals(5, fields.length, "invalid screenshot theme manifest line: " + line);
            ThemeDefinition previous = themes.put(
                fields[0],
                new ThemeDefinition(fields[1], fields[2], fields[3], fields[4])
            );
            assertTrue(previous == null, "duplicate screenshot theme slug: " + fields[0]);
        }
        assertTrue(!themes.isEmpty(), "the screenshot theme manifest must contain at least one theme");
        return themes;
    }

    private void assertLiveThemeColors(ThemeDefinition theme) {
        assertEquals(theme.name(), currentLookAndFeelName(), "active IntelliJ LAF");
        assertEquals(theme.editorBackground(), uiColor("Editor.background"), "editor UI color");
        assertEquals(theme.mainWindowBackground(), uiColor("MainWindow.background"), "Islands frame color");
        assertEquals(theme.accent(), uiColor("EditorTabs.underlinedBorderColor"), "active tab accent");
    }

    private String uiColor(String key) {
        return remoteRobot.callJs("""
            const color = javax.swing.UIManager.getColor('%s');
            if (color == null) {
                '';
            } else {
                function hex(channel) {
                    const value = java.lang.Integer.toHexString(channel & 255).toUpperCase();
                    return value.length === 1 ? '0' + value : value;
                }
                '#' + hex(color.getRed()) + hex(color.getGreen()) + hex(color.getBlue());
            }
            """.formatted(escapeJs(key)), true);
    }

    private void openShowcaseFile(IdeFrameFixture frame) {
        openDemoFile(frame, "ThemeShowcase.java");
    }

    private void openDemoFile(IdeFrameFixture frame, String fileName) {
        frame.runJs("""
            const helper = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component);
            const project = helper.getProject();
            const file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(
                project.getBasePath() + '/src/main/java/dev/jetplugins/demo/%s'
            );
            if (file == null) {
                throw new Error('%s was not found under ' + project.getBasePath());
            }
            const editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                .openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file), true);
            editor.getContentComponent().requestFocusInWindow();
            """.formatted(escapeJs(fileName), escapeJs(fileName)), true);
        waitUntil("selected editor " + fileName, Duration.ofSeconds(15), () -> Boolean.TRUE.equals(frame.callJs("""
            const project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject();
            const selected = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedFiles();
            selected.length > 0 && String(selected[0].getName()) === '%s';
            """.formatted(escapeJs(fileName)), true)));
        pause(Duration.ofMillis(500));
    }

    private void assertSampleCode(IdeFrameFixture frame) {
        String text = frame.textEditor(Duration.ofSeconds(10)).getEditor().getText();
        for (String expected : List.of("record ThemePreview", "List.of", "Map.of", ".stream()", "System.out::println")) {
            assertTrue(text.contains(expected), "sample code should demonstrate " + expected);
        }
    }

    private void showEditorWorkspace(IdeFrameFixture frame) {
        frame.runJs("""
            const project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject();
            const manager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
            const projectWindow = manager.getToolWindow('Project');
            if (projectWindow != null) projectWindow.show(null);
            const terminal = manager.getToolWindow('Terminal');
            if (terminal != null && terminal.isVisible()) terminal.hide(null);
            const problems = manager.getToolWindow('Problems');
            if (problems != null && problems.isVisible()) problems.hide(null);
            """, true);
        pause(Duration.ofSeconds(1));
    }

    private void showToolWindows(IdeFrameFixture frame) {
        frame.runJs("""
            const project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject();
            const manager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
            const projectWindow = manager.getToolWindow('Project');
            if (projectWindow != null) projectWindow.show(null);
            let lower = manager.getToolWindow('Terminal');
            if (lower == null) lower = manager.getToolWindow('Problems');
            if (lower == null) lower = manager.getToolWindow('Run');
            if (lower != null) lower.show(null);
            """, true);
        pause(Duration.ofSeconds(2));
    }

    private void showCompletion(IdeFrameFixture frame) {
        showEditorWorkspace(frame);
        openShowcaseFile(frame);
        waitForProject(frame);
        TextEditorFixture textEditor = frame.textEditor(Duration.ofSeconds(10));
        String marker = "System.out::p";
        int offset = textEditor.getEditor().getText().indexOf(marker) + marker.length();
        assertTrue(offset >= marker.length(), "completion marker must exist in the showcase editor");

        frame.runJs("""
            const settings = com.intellij.codeInsight.CodeInsightSettings.getInstance();
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false;
            settings.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION = false;
            """, true);
        textEditor.getEditor().clickOnOffset(offset, MouseButton.LEFT_BUTTON, 1);

        // Ensure the mouse release is fully processed before pressing Control. Without this gap,
        // macOS can interpret the still-active click as Control-click and open a context menu.
        pause(Duration.ofSeconds(1));
        new Keyboard(remoteRobot).hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SPACE);
        try {
            waitUntil("completion popup from Ctrl+Space", Duration.ofSeconds(8), () ->
                isCompletionVisible(frame));
        } catch (AssertionError shortcutWasIntercepted) {
            invokeCodeCompletionAction(frame);
            waitUntil("completion popup from IntelliJ action", Duration.ofSeconds(20), () ->
                isCompletionVisible(frame));
        }
    }

    private void invokeCodeCompletionAction(IdeFrameFixture frame) {
        frame.runJs("""
            const project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject();
            const editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                new java.lang.Runnable({run: function() {
                    com.intellij.codeInsight.completion.CodeCompletionHandlerBase.createHandler(
                        com.intellij.codeInsight.completion.CompletionType.BASIC
                    ).invokeCompletion(project, editor, 1);
                }})
            );
            """);
    }

    private boolean isCompletionVisible(IdeFrameFixture frame) {
        return Boolean.TRUE.equals(frame.callJs("""
            const project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject();
            const editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
            com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(editor) != null;
            """, true));
    }

    private void hideCompletion(IdeFrameFixture frame) {
        frame.runJs("""
            const project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject();
            com.intellij.codeInsight.lookup.LookupManager.getInstance(project).hideActiveLookup();
            """, true);
    }

    private void showSettings(IdeFrameFixture frame, ThemeDefinition theme) {
        frame.runJs("""
            const project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject();
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                new java.lang.Runnable({run: function() {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, 'Appearance & Behavior');
                }})
            );
            """);

        waitUntil("Settings dialog", Duration.ofSeconds(30), this::hasVisibleDialog);
        remoteRobot.find(
            ComponentFixture.class,
            byXpath("//div[@text='Appearance']"),
            Duration.ofSeconds(15)
        ).click();
        waitUntil("Appearance settings for " + theme.name(), Duration.ofSeconds(30),
            this::hasVisibleSettingsComboBox);
        pause(Duration.ofSeconds(1));
    }

    private boolean hasVisibleSettingsComboBox() {
        return Boolean.TRUE.equals(remoteRobot.callJs("""
            function containsComboBox(container) {
                if (container instanceof javax.swing.JComboBox && container.isShowing()) return true;
                if (container instanceof java.awt.Container) {
                    const children = container.getComponents();
                    for (let index = 0; index < children.length; index++) {
                        if (containsComboBox(children[index])) return true;
                    }
                }
                return false;
            }
            const windows = java.awt.Window.getWindows();
            let found = false;
            for (let index = 0; index < windows.length; index++) {
                if (windows[index] instanceof java.awt.Dialog && windows[index].isShowing()
                    && containsComboBox(windows[index])) {
                    found = true;
                    break;
                }
            }
            found;
            """, true));
    }

    private void showDiff(IdeFrameFixture frame) {
        frame.runJs("""
            const project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject();
            const factory = com.intellij.diff.DiffContentFactory.getInstance();
            const before = factory.create(
                '"colors": {\\n  "bgEditor": "#282C34",\\n  "accent": "#3691FF"\\n}\\n'
            );
            const after = factory.create(
                '"colors": {\\n  "bgEditor": "#1F2329",\\n  "accent": "#4B9CFF"\\n}\\n'
            );
            const request = new com.intellij.diff.requests.SimpleDiffRequest(
                'Bluloco Dark → Midnight', before, after, 'Dark', 'Midnight'
            );
            com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
            """, true);
        pause(Duration.ofSeconds(3));
    }

    private boolean hasVisibleDialog() {
        return Boolean.TRUE.equals(remoteRobot.callJs("""
            const windows = java.awt.Window.getWindows();
            let found = false;
            for (let index = 0; index < windows.length; index++) {
                if (windows[index] instanceof java.awt.Dialog && windows[index].isShowing()) {
                    found = true;
                    break;
                }
            }
            found;
            """, true));
    }

    private void closeAuxiliaryWindows(IdeFrameFixture frame) {
        frame.runJs("""
            const fixtureWindows = new java.util.IdentityHashMap();
            fixtureWindows.put(component, java.lang.Boolean.TRUE);
            const windows = java.awt.Window.getWindows();
            for (let index = 0; index < windows.length; index++) {
                if (fixtureWindows.get(windows[index]) == null && windows[index].isShowing()) {
                    windows[index].dispose();
                }
            }
            """, true);
        pause(Duration.ofMillis(500));
    }

    private void closeSelectedDiffTab(IdeFrameFixture frame) {
        frame.runJs("""
            const project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject();
            const manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            const selected = manager.getSelectedFiles();
            com.intellij.openapi.application.WriteIntentReadAction.run(
                new java.lang.Runnable({run: function() {
                    for (let index = 0; index < selected.length; index++) {
                        if (String(selected[index].getName()) !== 'ThemeShowcase.java') {
                            manager.closeFile(selected[index]);
                        }
                    }
                }})
            );
            """, true);
        pause(Duration.ofMillis(500));
    }

    private BufferedImage capture(IdeFrameFixture frame, String theme, String scenario) throws IOException {
        bringIdeWindowsToFront(frame);
        BufferedImage fullScreen = remoteRobot.getScreenshot();
        Point location = frame.getLocationOnScreen();
        int frameWidth = ((Number) frame.callJs("component.getWidth();", true)).intValue();
        int frameHeight = ((Number) frame.callJs("component.getHeight();", true)).intValue();
        int logicalScreenWidth = ((Number) remoteRobot.callJs(
            "java.awt.Toolkit.getDefaultToolkit().getScreenSize().width;", true
        )).intValue();
        int logicalScreenHeight = ((Number) remoteRobot.callJs(
            "java.awt.Toolkit.getDefaultToolkit().getScreenSize().height;", true
        )).intValue();

        double scaleX = fullScreen.getWidth() / (double) logicalScreenWidth;
        double scaleY = fullScreen.getHeight() / (double) logicalScreenHeight;
        int x = clamp((int) Math.round(location.x * scaleX), 0, fullScreen.getWidth() - 1);
        int y = clamp((int) Math.round(location.y * scaleY), 0, fullScreen.getHeight() - 1);
        int width = Math.min((int) Math.round(frameWidth * scaleX), fullScreen.getWidth() - x);
        int height = Math.min((int) Math.round(frameHeight * scaleY), fullScreen.getHeight() - y);

        BufferedImage normalized;
        if (width > 200 && height > 200) {
            normalized = resize(fullScreen.getSubimage(x, y, width, height), CAPTURE_WIDTH, CAPTURE_HEIGHT);
        } else {
            normalized = resize(frame.getScreenshot(false), CAPTURE_WIDTH, CAPTURE_HEIGHT);
        }

        Path rawDirectory = screenshotDirectory.resolve("raw").resolve(theme);
        Files.createDirectories(rawDirectory);
        Path output = rawDirectory.resolve(scenario + ".png");
        ImageIO.write(normalized, "png", output.toFile());

        assertEquals(CAPTURE_WIDTH, normalized.getWidth(), "Marketplace screenshot width");
        assertEquals(CAPTURE_HEIGHT, normalized.getHeight(), "Marketplace screenshot height");
        return normalized;
    }

    private void bringIdeWindowsToFront(IdeFrameFixture frame) {
        frame.runJs("""
            component.toFront();
            component.requestFocus();
            const windows = java.awt.Window.getWindows();
            for (let index = 0; index < windows.length; index++) {
                if (windows[index] instanceof java.awt.Dialog && windows[index].isShowing()) {
                    windows[index].toFront();
                }
            }
            """, true);
        pause(Duration.ofMillis(500));
    }

    private void writeThemeShowcase(
        int index,
        String slug,
        ThemeDefinition theme,
        BufferedImage editor
    ) throws IOException {
        int width = CAPTURE_WIDTH;
        int height = CAPTURE_HEIGHT;
        BufferedImage showcase = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = showcase.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(editor, 0, 0, width, height, null);
        graphics.setColor(new Color(0xDD15181D, true));
        graphics.fillRoundRect(24, 22, 430, 62, 18, 18);
        graphics.setColor(Color.decode(theme.accent()));
        graphics.fillRoundRect(24, 22, 7, 62, 7, 7);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        graphics.setColor(Color.WHITE);
        graphics.drawString(theme.name(), 47, 49);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        graphics.setColor(new Color(0xC7CEDB));
        graphics.drawString("Vivid code · cohesive IntelliJ Islands", 47, 72);
        graphics.dispose();

        Path themesDirectory = screenshotDirectory.resolve("themes");
        Files.createDirectories(themesDirectory);
        ImageIO.write(
            showcase,
            "png",
            themesDirectory.resolve("%02d-%s-sample-code.png".formatted(index, slug)).toFile()
        );
    }

    private void writeComparison(
        int index,
        String scenario,
        Map<String, ThemeDefinition> themes,
        Map<String, Map<String, BufferedImage>> captures
    ) throws IOException {
        int width = CAPTURE_WIDTH;
        int height = CAPTURE_HEIGHT;
        BufferedImage comparison = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = comparison.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        int column = 0;
        for (Map.Entry<String, ThemeDefinition> themeEntry : themes.entrySet()) {
            BufferedImage capture = captures.get(themeEntry.getKey()).get(scenario);
            assertNotNull(capture, "missing " + themeEntry.getValue().name() + " capture for " + scenario);
            int start = column * width / themes.size();
            int end = (column + 1) * width / themes.size();
            graphics.setClip(start, 0, end - start, height);
            if ("settings".equals(scenario)) {
                // Keep the Appearance panel and theme selector visible in every slice. A full-frame
                // split would hide the left-hand controls from themes in the right-hand columns.
                graphics.drawImage(capture, start, 0, end, height, 390, 0, 990, height, null);
            } else {
                graphics.drawImage(capture, 0, 0, width, height, null);
            }
            graphics.setClip(null);

            String label = themeEntry.getValue().name();
            int fontSize = themes.size() <= 2 ? 20 : 15;
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            int labelWidth = graphics.getFontMetrics().stringWidth(label);
            int pillWidth = labelWidth + 32;
            int pillX = start + Math.max(12, (end - start - pillWidth) / 2);
            graphics.setColor(new Color(0xDD15181D, true));
            graphics.fillRoundRect(pillX, 20, pillWidth, 42, 16, 16);
            graphics.setColor(Color.decode(themeEntry.getValue().accent()));
            graphics.fillRoundRect(pillX, 20, 6, 42, 6, 6);
            graphics.setColor(Color.WHITE);
            graphics.drawString(label, pillX + 20, 48);

            if (column > 0) {
                graphics.setColor(new Color(0xE8FFFFFF, true));
                graphics.fillRect(start - 1, 0, 2, height);
            }
            column++;
        }

        String annotation = scenarioAnnotation(scenario);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        int annotationWidth = graphics.getFontMetrics().stringWidth(annotation) + 38;
        int annotationX = (width - annotationWidth) / 2;
        graphics.setColor(new Color(0xE815181D, true));
        graphics.fillRoundRect(annotationX, height - 65, annotationWidth, 43, 18, 18);
        graphics.setColor(Color.WHITE);
        graphics.drawString(annotation, annotationX + 19, height - 37);
        graphics.dispose();

        String themeSlug = String.join("-vs-", themes.keySet());
        String fileName = "%02d-%s-%s.png".formatted(index, scenario, themeSlug);
        ImageIO.write(comparison, "png", screenshotDirectory.resolve(fileName).toFile());
    }

    private static String scenarioAnnotation(String scenario) {
        return switch (scenario) {
            case "editor" -> "Vivid syntax with comfortable contrast";
            case "tool-windows" -> "A cohesive workspace, edge to edge";
            case "completion" -> "Completion stays clear and in context";
            case "settings" -> "Choose Bluloco in Settings → Appearance";
            case "diff" -> "Changes stand out without visual noise";
            default -> displayName(scenario);
        };
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private static void assertColorCoverage(BufferedImage image, String color, double minimumRatio) {
        Color expected = Color.decode(color);
        int matched = 0;
        int sampled = 0;
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                Color actual = new Color(image.getRGB(x, y), true);
                if (Math.abs(actual.getRed() - expected.getRed()) <= 3
                    && Math.abs(actual.getGreen() - expected.getGreen()) <= 3
                    && Math.abs(actual.getBlue() - expected.getBlue()) <= 3) {
                    matched++;
                }
                sampled++;
            }
        }
        double ratio = matched / (double) sampled;
        assertTrue(ratio >= minimumRatio,
            "Expected " + color + " to cover at least " + minimumRatio + " of the rendered frame, got " + ratio);
    }

    private static double pixelDifferenceRatio(BufferedImage first, BufferedImage second) {
        long different = 0;
        long sampled = 0;
        for (int y = 0; y < first.getHeight(); y += 4) {
            for (int x = 0; x < first.getWidth(); x += 4) {
                Color a = new Color(first.getRGB(x, y));
                Color b = new Color(second.getRGB(x, y));
                int distance = Math.abs(a.getRed() - b.getRed())
                    + Math.abs(a.getGreen() - b.getGreen())
                    + Math.abs(a.getBlue() - b.getBlue());
                if (distance > 18) {
                    different++;
                }
                sampled++;
            }
        }
        return different / (double) sampled;
    }

    private static double averageLuminance(BufferedImage image) {
        double total = 0;
        long sampled = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                Color color = new Color(image.getRGB(x, y));
                total += 0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue();
                sampled++;
            }
        }
        return total / sampled;
    }

    private static String displayName(String scenario) {
        String[] words = scenario.split("-");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static String escapeJs(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void waitUntil(String description, Duration timeout, CheckedBoolean condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.get()) {
                    return;
                }
            } catch (RuntimeException failure) {
                lastFailure = failure;
            } catch (Exception failure) {
                lastFailure = new RuntimeException(failure);
            }
            pause(Duration.ofMillis(500));
        }
        AssertionError error = new AssertionError("Timed out waiting for " + description);
        if (lastFailure != null) error.initCause(lastFailure);
        throw error;
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }

    private record ThemeDefinition(
        String name,
        String editorBackground,
        String mainWindowBackground,
        String accent
    ) {}

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }

    @FixtureName(name = "IntelliJ IDE frame")
    @DefaultXpath(by = "IdeFrameImpl type", xpath = "//div[@class='IdeFrameImpl']")
    public static final class IdeFrameFixture extends CommonContainerFixture {
        public IdeFrameFixture(RemoteRobot remoteRobot, RemoteComponent remoteComponent) {
            super(remoteRobot, remoteComponent);
        }
    }
}

package com.cuwano.afinador;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class LauncherBootstrapTest {
    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
    private static final String MAIN_ACTION = "android.intent.action.MAIN";
    private static final String LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER";

    @Test
    public void mainActivityIsAnAndroidActivity() throws Exception {
        Class<?> mainActivity = Class.forName(
                "com.cuwano.afinador.MainActivity",
                false,
                getClass().getClassLoader());

        assertTrue("MainActivity must extend android.app.Activity",
                Activity.class.isAssignableFrom(mainActivity));
    }

    @Test
    public void manifestExposesMainActivityAsLauncher() throws Exception {
        Element launcherActivity = findMainActivity(readManifest());

        assertNotNull("AndroidManifest.xml must declare MainActivity", launcherActivity);
        assertTrue("MainActivity must have an intent-filter with MAIN and LAUNCHER",
                hasLauncherIntentFilter(launcherActivity));
    }

    private static Element readManifest() throws Exception {
        Path manifest = Path.of("src", "main", "AndroidManifest.xml");
        assertTrue("Expected app manifest at " + manifest.toAbsolutePath(), Files.isRegularFile(manifest));

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(manifest.toFile()).getDocumentElement();
    }

    private static Element findMainActivity(Element manifest) {
        NodeList activities = manifest.getElementsByTagName("activity");
        for (int index = 0; index < activities.getLength(); index++) {
            Element activity = (Element) activities.item(index);
            String name = activity.getAttributeNS(ANDROID_NAMESPACE, "name");
            if (name.equals(".MainActivity") || name.equals("com.cuwano.afinador.MainActivity")) {
                return activity;
            }
        }
        return null;
    }

    private static boolean hasLauncherIntentFilter(Element activity) {
        NodeList filters = activity.getElementsByTagName("intent-filter");
        for (int index = 0; index < filters.getLength(); index++) {
            Element filter = (Element) filters.item(index);
            if (hasNamedChild(filter, "action", MAIN_ACTION)
                    && hasNamedChild(filter, "category", LAUNCHER_CATEGORY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNamedChild(Element parent, String tag, String expectedName) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int index = 0; index < children.getLength(); index++) {
            Element child = (Element) children.item(index);
            if (expectedName.equals(child.getAttributeNS(ANDROID_NAMESPACE, "name"))) {
                return true;
            }
        }
        return false;
    }
}

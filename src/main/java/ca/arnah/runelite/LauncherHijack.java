package ca.arnah.runelite;

import com.google.common.io.ByteStreams;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;

import javax.inject.Inject;
import javax.swing.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Arnah
 * @since Nov 07, 2020
 */
public class LauncherHijack{
	@Inject
	PluginManager pluginManager;
	@Inject
	EventBus eventBus;
	SimpleClassLoader simpleLoader = new SimpleClassLoader(getClass().getClassLoader());

	public LauncherHijack(){
		new Thread(()->{
			// First we need to grab the ClassLoader the launcher uses to launch the client.
			ClassLoader objClassLoader;
			loop:
			while(true){
				objClassLoader = (ClassLoader) UIManager.get("ClassLoader");
				if(objClassLoader != null){
					for(Package pack : objClassLoader.getDefinedPackages()){
						if(pack.getName().equals("net.runelite.client.rs")){
							break loop;
						}
					}
				}
				try{
					Thread.sleep(100);
				}catch(Exception ex){
					ex.printStackTrace();
				}
			}
			System.out.println("Classloader found");
			try{
				URLClassLoader classLoader = (URLClassLoader) objClassLoader;
				
				// Add our hijack client to the classloader
				Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
				addUrl.setAccessible(true);
				
				URI uri = LauncherHijack.class.getProtectionDomain().getCodeSource().getLocation().toURI();
				if(uri.getPath().endsWith("classes/")){// Intellij
					uri = uri.resolve("..");
				}
				if(!uri.getPath().endsWith(".jar")){
					uri = uri.resolve("RuneLiteHijack.jar");
				}
				addUrl.invoke(classLoader, uri.toURL());
				System.out.println(uri.getPath());
				
				// Execute our code inside the runelite client classloader
				Class<?> clazz = classLoader.loadClass(ClientHijack.class.getName());
				clazz.getConstructor().newInstance();
			}catch(Exception ex){
				ex.printStackTrace();
			}

			try {
				List<Path> jarPaths = findJars();
				List<Class<?>> toLoad = new ArrayList<>();
				List<ClassByte> classes = new ArrayList<>();
				for (Path jarPath : jarPaths) {
					classes.addAll(listFilesInJar(jarPath));
				}
				int numLoaded = 0;
				do {
					numLoaded = 0;
					for (int i1 = classes.size() - 1; i1 >= 0; i1--) {
						Class<?> loaded = simpleLoader.loadClass(classes.get(i1).name, classes.get(i1).bytes);
						if (loaded != null) {
							numLoaded++;
							classes.remove(i1);
						}
						if (loaded != null && loaded.getSuperclass() != null && loaded.getSuperclass().equals(Plugin.class)) {
							toLoad.add(loaded);
						}
					}
				} while (numLoaded != 0);
				List<Plugin> loaded = pluginManager.loadPlugins(toLoad, null);
				loaded = loaded.stream().filter(Objects::nonNull).collect(Collectors.toList());
				List<Plugin> finalLoaded = loaded;
				SwingUtilities.invokeAndWait(() ->
				{
					try {
						for (Plugin plugin : finalLoaded) {
							pluginManager.loadDefaultPluginConfiguration(Collections.singleton(plugin));
							pluginManager.startPlugin(plugin);
						}
					} catch (PluginInstantiationException e) {
						e.printStackTrace();
					}
				});
				eventBus.post(new ExternalPluginsChanged());
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}).start();
	}
	
	public static void main(String[] args){
		// Force disable the "JVMLauncher", was just easiest way to do what I wanted at the time.
		System.setProperty("runelite.launcher.nojvm", "true");
		// Was renamed in https://github.com/runelite/launcher/commit/9086bb5539fce6ccdea148b03ff05efde21e675e
		System.setProperty("runelite.launcher.reflect", "true");
		new LauncherHijack();
		// Launcher.main(args);
		try{
			Class<?> clazz = Class.forName("net.runelite.launcher.Launcher");
			clazz.getMethod("main", String[].class).invoke(null, (Object) args);
		}catch(Exception ignored){
		}
		System.out.println("Launcher finished");
	}

	public List<Path> findJars() {
		try {
			Files.createDirectories(RuneLite.RUNELITE_DIR.toPath().resolve("externalplugins"));
			Files.createDirectories(RuneLite.RUNELITE_DIR.toPath().resolve("sideloaded-plugins"));
		} catch (IOException e) {
			// ignore
		}
		try {
			List<Path> files = new ArrayList<>();
			try (Stream<Path> walkable = Files.walk(RuneLite.RUNELITE_DIR.toPath().resolve("externalplugins"))) {

				walkable.filter(Files::isRegularFile)
						.filter(f -> f.toString().endsWith(".jar"))
						.forEach(files::add);
			}
			try (Stream<Path> walkable = Files.walk(RuneLite.RUNELITE_DIR.toPath().resolve("sideloaded-plugins"))) {
				walkable.filter(Files::isRegularFile)
						.filter(f -> f.toString().endsWith(".jar"))
						.forEach(files::add);
			}
			return files;
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return new ArrayList<>();
	}

	public List<ClassByte> listFilesInJar(Path jarPath) {
		List<ClassByte> classes = new ArrayList<>();
		try (JarFile jarFile1 = new JarFile(jarPath.toFile())) {
			jarFile1.stream().forEach(jarEntry ->
			{
				if (jarEntry == null || jarEntry.isDirectory()) return;
				if (!jarEntry.getName().contains(".class")) {
					try (InputStream inputStream = jarFile1.getInputStream(jarEntry)) {
						simpleLoader.resources.put(jarEntry.getName(),
								new ByteArrayInputStream(SimpleClassLoader.getBytes(inputStream)));
					} catch (IOException ioException) {
					}
				}
				try (InputStream inputStream = jarFile1.getInputStream(jarEntry)) {
					classes.add(new ClassByte(ByteStreams.toByteArray(inputStream),
							jarEntry.getName().replace('/', '.').substring(0,
									jarEntry.getName().length() - 6)));
				} catch (IOException ioException) {
					System.out.println("Could not obtain class entry for " + jarEntry.getName());
				}
			});
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return classes;
	}
}
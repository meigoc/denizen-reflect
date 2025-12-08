package meigo.denizen.reflect.util;

import com.denizenscript.denizencore.utilities.debugging.Debug;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class LibraryLoader {
    private static ClassLoader classLoader = LibraryLoader.class.getClassLoader();

    private static final PathMatcher MATCHER =
            FileSystems.getDefault().getPathMatcher("glob:**/*.jar");

    private static class LibraryVisitor extends SimpleFileVisitor<Path> {
        private final List<URL> urls = new ArrayList<>();

        @NotNull
        @Override
        public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
            if (MATCHER.matches(file)) {
                Debug.log("denizen-reflect", "Loaded external library " + file.getFileName());
                urls.add(file.toUri().toURL());
            }
            return super.visitFile(file, attrs);
        }

        public URL[] getUrls() {
            return urls.toArray(new URL[0]);
        }
    }

    public static void loadLibraries(Path dataFolder) throws IOException {
        if (Files.isDirectory(dataFolder)) {
            LibraryVisitor visitor = new LibraryVisitor();
            Files.walkFileTree(dataFolder, visitor);
            classLoader = new URLClassLoader(visitor.getUrls(), LibraryLoader.class.getClassLoader());
        } else {
            Files.createDirectory(dataFolder);
        }
    }

    public static ClassLoader getClassLoader() {
        return classLoader;
    }
}
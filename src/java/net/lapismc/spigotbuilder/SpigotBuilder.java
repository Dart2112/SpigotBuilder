package net.lapismc.spigotbuilder;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Scanner;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

class SpigotBuilder {

    private Process buildTools;
    private boolean showBuildTools = false;

    SpigotBuilder(String version, boolean update) {
        log("Starting SpigotBuilder");
        //Create build directory if non-existent
        File buildDir = new File("./build");
        if (!buildDir.exists()) {
            buildDir.mkdir();
        }
        //Check if we have the latest version
        File currentSpigotJar = new File("./Spigot.jar");
        log("Checking for Spigot version");
        int mustUpdate = mustUpdate(currentSpigotJar);
        if (mustUpdate == 2 && update || mustUpdate == 1) {
            log("Compiling new Spigot jar");
            //Run build tools if we dont have the latest version
            runBuildTools(buildDir, currentSpigotJar, version, mustUpdate == 2);
        } else if (!update) {
            log("Update is disabled, starting server");
            log("set second argument to true or remove it to update");
        }
        //Set EULA file before starting the server
        placeEula();
        log("Starting the server");
        startServer(currentSpigotJar);
        if (buildTools != null && buildTools.isAlive())
            buildTools.destroyForcibly();
        System.exit(0);
    }

    /**
     * Check for an update to Spigot
     *
     * @param currentSpigotJar The current jar file for spigot
     * @return Returns 0 if there is no update, 1 if there isn't a valid jar, 2 if the update could be
     * downloaded in the background
     */
    private int mustUpdate(File currentSpigotJar) {
        File updatedJar = new File("./Spigot-UPDATED");
        if (updatedJar.exists()) {
            try {
                FileUtils.deleteQuietly(currentSpigotJar);
                FileUtils.moveFile(updatedJar, currentSpigotJar);
                FileUtils.deleteQuietly(updatedJar);
                log("Previously compiled update installed!");
            } catch (IOException e) {
                log("Failed to install update");
            }
            return 0;
        }
        int mustUpdate = 0;
        if (currentSpigotJar.exists()) {
            try {
                String version = getVersion(currentSpigotJar);
                String[] parts = version.substring("git-Spigot-".length()).split("-");
                int spigotVersions = getDistance("spigot", parts[0]);
                int craftBukkitVersions = getDistance("craftbukkit", parts[1]);
                //If the difference is 0 we dont update
                mustUpdate = !(spigotVersions == 0 && craftBukkitVersions == 0) ? 2 : 0;
                if (mustUpdate == 2) {
                    log("You are " + (spigotVersions + craftBukkitVersions) + " version(s) behind the latest Spigot");
                } else {
                    log("You are on the latest version of Spigot");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            log("No Spigot jar found");
            mustUpdate = 1;
        }
        return mustUpdate;
    }

    private void runBuildTools(File buildDir, File currentSpigotJar, String version, boolean background) {
        //Run build tools and copy result into server
        //Check if build tools exists
        File buildJar = new File("./build" + File.separator + "BuildTools.jar");
        if (!buildJar.exists()) {
            try {
                buildJar.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        //Always download the latest build tools
        try {
            //Download the jar file
            URL website = new URL("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild" +
                    "/artifact/target/BuildTools.jar");
            ReadableByteChannel rbc = Channels.newChannel(website.openStream());
            FileOutputStream fos = new FileOutputStream(buildJar);
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //Run build tools and wait for it to complete
        ProcessBuilder pb;
        if (version.equals("")) {
            pb = new ProcessBuilder("java", "-Xmx512M", "-jar",
                    buildJar.getAbsolutePath());
        } else {
            pb = new ProcessBuilder("java", "-Xmx512M", "-jar",
                    buildJar.getAbsolutePath(), "--rev", version);
        }

        pb.directory(buildDir);
        try {
            if (background) {
                log("Starting BuildTools, the update will be installed on the next server start if it is ready");
            } else {
                log("Starting BuildTools, all BuildTools output will not have time stamps");
            }
            buildTools = startProcess(pb, false, !background);
            if (!background) {
                buildTools.waitFor();
            } else {
                new Thread(() -> {
                    try {
                        while (buildTools.isAlive()) {
                            Thread.sleep(1000);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (copyFile(getCompiledJar(buildDir), new File("./Spigot-UPDATED"))) {
                        log("Update downloaded, it will be installed the next time the server starts");
                    } else {
                        log("The jar file could not be installed, this may be because build tools failed" +
                                " or because the destination file is in use");
                    }
                }).start();
            }
            if (background) {
                new Thread(() -> {
                    try {
                        int i = 0;
                        while (buildTools.isAlive()) {
                            i++;
                            if (i >= 30) {
                                if (!showBuildTools)
                                    log("Build tools is still running, stopping the server will terminate BuildTools" +
                                            ", use tbt or togglebuildtools to show the output");
                                i = 0;
                            }
                            if (showBuildTools) {
                                InputStream is = buildTools.getInputStream();
                                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                                String line;
                                while ((line = reader.readLine()) != null && showBuildTools) {
                                    System.out.println(line);
                                }
                            }
                            Thread.sleep(1000);
                        }
                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }).start();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.out.println("Unable to run build tools! See the above error");
            return;
        }
        if (!background) {
            log("Copying the compiled jar");
            if (copyFile(getCompiledJar(buildDir), currentSpigotJar)) {
                log("Jar file installed");
            } else {
                log("The jar file could not be installed, this may be because build tools failed or because" +
                        " the destination file is in use");
            }
        }
    }

    private void placeEula() {
        File eula = new File("./eula.txt");
        URL url = this.getClass().getClassLoader().getResource("eula.txt");
        try {
            URLConnection connection = Objects.requireNonNull(url).openConnection();
            connection.setUseCaches(false);
            InputStream stream = connection.getInputStream();
            int len;
            FileOutputStream out = new FileOutputStream(eula);
            byte[] buf = new byte[1024];
            while ((len = stream.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.close();
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startServer(File spigotJar) {
        try {
            //Start the server without the vanilla GUI
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", spigotJar.getAbsolutePath(), "--nogui");
            startProcess(pb, true, true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Process startProcess(ProcessBuilder pb, boolean allowInput, boolean showOutput) throws IOException {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        if (allowInput) {
            Thread thread = new Thread(() -> {
                while (process.isAlive()) {
                    Scanner scanner = new Scanner(System.in);
                    while (scanner.hasNextLine()) {
                        String input = scanner.nextLine() + "\n";
                        if (input.toLowerCase().startsWith("togglebuildtools") ||
                                input.toLowerCase().startsWith("tbt")) {
                            showBuildTools = !showBuildTools;
                        } else {
                            try {
                                OutputStream out = process.getOutputStream();
                                out.write(input.getBytes());
                                out.flush();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            });
            thread.start();
        }
        if (showOutput) {
            InputStream is = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
        return process;
    }

    private int getDistance(String repo, String hash) {
        try {
            //noinspection UnstableApiUsage
            try (BufferedReader reader = Resources.asCharSource(
                    new URL("https://hub.spigotmc.org/stash/rest/api/1.0/projects/SPIGOT/repos/" + repo +
                            "/commits?since=" + URLEncoder.encode(hash, StandardCharsets.UTF_8.toString()) + "&withCounts=true"),
                    Charsets.UTF_8).openBufferedStream()) {
                JSONObject obj = (JSONObject) new JSONParser().parse(reader);
                return ((Number) obj.get("totalCount")).intValue();
            } catch (ParseException ex) {
                ex.printStackTrace();
                return -1;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
    }

    private File getCompiledJar(File buildDir) {
        File newSpigotJar = null;
        for (File f : Objects.requireNonNull(buildDir.listFiles())) {
            if (!f.isDirectory() && f.getName().startsWith("spigot-") && f.getName().endsWith(".jar")) {
                newSpigotJar = f;
            }
        }
        return newSpigotJar;
    }

    private boolean copyFile(File source, File dest) {
        try {
            if (source.getName().endsWith(".jar")) {
                //Verify the files integrity as a jar
                getVersion(source);
                String version = getVersion(source);
                if (!version.startsWith("git-Spigot-")) {
                    return false;
                }
            } else {
                return false;
            }
            FileUtils.copyFile(source, dest);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private String getVersion(File jarFile) throws IOException {
        InputStream stream = new FileInputStream(jarFile);
        JarInputStream jarStream = new JarInputStream(stream);
        Manifest mf = jarStream.getManifest();
        return mf.getMainAttributes().getValue("Implementation-Version");
    }

    private void log(String message) {
        String timeStamp = new SimpleDateFormat("[dd/MM/yyyy HH:mm:ss]: ").format(new Date());
        System.out.println(timeStamp + message);
    }

}

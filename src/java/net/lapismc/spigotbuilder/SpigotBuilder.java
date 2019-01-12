package net.lapismc.spigotbuilder;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.bukkit.util.FileUtil;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Scanner;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

class SpigotBuilder {

    SpigotBuilder() {
        log("Starting SpigotBuilder");
        //Create build directory if non-existent
        File buildDir = new File("./Build");
        if (!buildDir.exists()) {
            buildDir.mkdir();
        }
        //Check if we have the latest version
        File currentSpigotJar = new File("./Spigot.jar");
        log("Checking for updates to Spigot");
        if (mustUpdate(currentSpigotJar)) {
            log("Compiling the latest version");
            //Run build tools if we dont have the latest version
            runBuildTools(buildDir, currentSpigotJar);
        }
        //Set EULA file before starting the server
        placeEula();
        log("Starting the server");
        startServer(currentSpigotJar);
        System.exit(0);
    }

    private boolean mustUpdate(File currentSpigotJar) {
        boolean mustUpdate = false;
        if (currentSpigotJar.exists()) {
            try {
                InputStream stream = new FileInputStream(currentSpigotJar);
                JarInputStream jarStream = new JarInputStream(stream);
                Manifest mf = jarStream.getManifest();
                String version = mf.getMainAttributes().getValue("Implementation-Version");
                String[] parts = version.substring("git-Spigot-".length()).split("-");
                int spigotVersions = getDistance(parts[0]);
                //If the difference is 0 we dont update
                mustUpdate = spigotVersions != 0;
                if (mustUpdate) {
                    log("You are " + spigotVersions + " behind the latest Spigot version");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            log("No Spigot jar found");
            mustUpdate = true;
        }
        return mustUpdate;
    }

    private void runBuildTools(File buildDir, File currentSpigotJar) {
        //Run build tools and copy result into server
        //Check if build tools exists
        File buildJar = new File("./Build" + File.separator + "BuildTools.jar");
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
            URL website = new URL("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar");
            ReadableByteChannel rbc = Channels.newChannel(website.openStream());
            FileOutputStream fos = new FileOutputStream(buildJar);
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //Run build tools and wait for it to complete
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", buildJar.getAbsolutePath());
        pb.directory(buildDir);
        try {
            log("Starting BuildTools, all BuildTools output will not have time stamps");
            Process process = startProcess(pb);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.out.println("Unable to run build tools! See the above error");
            return;
        }
        log("Copying the compiled jar");
        File newSpigotJar = new File("");
        for (File f : Objects.requireNonNull(buildDir.listFiles())) {
            if (!f.isDirectory() | f.getName().startsWith("spigot-")) {
                newSpigotJar = f;
            }
        }
        FileUtil.copy(newSpigotJar, currentSpigotJar);
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
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", spigotJar.getAbsolutePath());
            startProcess(pb);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Process startProcess(ProcessBuilder pb) throws IOException {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        Thread thread = new Thread(() -> {
            while (process.isAlive()) {
                Scanner scanner = new Scanner(System.in);
                while (scanner.hasNext()) {
                    String input = scanner.next() + "\n";
                    try {
                        OutputStream out = process.getOutputStream();
                        out.write(input.getBytes());
                        out.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        thread.start();
        InputStream is = process.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }
        return process;
    }

    private int getDistance(String hash) {
        String repo = "spigot";
        try {
            //noinspection UnstableApiUsage
            try (BufferedReader reader = Resources.asCharSource(
                    new URL("https://hub.spigotmc.org/stash/rest/api/1.0/projects/SPIGOT/repos/" + repo +
                            "/commits?since=" + URLEncoder.encode(hash, "UTF-8") + "&withCounts=true"),
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

    private void log(String message) {
        String timeStamp = new SimpleDateFormat("[dd/MM/yyyy HH:mm:ss]: ").format(new Date());
        System.out.println(timeStamp + message);
    }

}

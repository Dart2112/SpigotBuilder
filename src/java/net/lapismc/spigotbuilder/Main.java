package net.lapismc.spigotbuilder;

public class Main {

    public static void main(String[] args) {
        String version = "";
        boolean update = true;
        if(args.length > 0){
            version = args[0];
        }
        if(args.length > 1){
            update = args[0].equalsIgnoreCase("true");
        }
        new SpigotBuilder(version, update);
    }

}

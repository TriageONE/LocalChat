import com.sun.source.doctree.SystemPropertyTree;

import java.io.IOException;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class DistributorHub extends Thread {
    protected static DatagramSocket socket;
    boolean running = true;
    private static final Vector<ConnectedConfig> currentConnections = new Vector<>();
    private final LinkedHashMap<String, LinkedHashMap<Long, String>> channelHistory = new LinkedHashMap<>();


    private InetAddress currentAddress;
    private int currentPort;

    public DistributorHub(int port) throws SocketException{
        socket = new DatagramSocket(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (ConnectedConfig config : currentConnections){
                try {
                    config.sendMessage("%END:");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }));
    }

    public void endServer(){
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public void run() {

        channelHistory.put("1", new LinkedHashMap<>());
        System.out.println("[SERVER] SERVER PLATFORM STARTED");
        while (running) {
            byte[] buf = new byte[256];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                continue;
            }

            this.currentAddress = packet.getAddress();
            this.currentPort = packet.getPort();
            packet = new DatagramPacket(buf, buf.length, currentAddress, currentPort);
            String received = new String(packet.getData(), 0, packet.getLength());
            received = received.trim();
            //We have recieved the packet and have a string,
            /*
            there's commands that clients can send:
                %CON connect to the service as a new client
                %DIS disconnect if they match
                %MSG send a message to their current channel
                %SET: set various settings like channel
                %REQ: ask for previous messages
                %KAL: keepalive

            Commands are constant length and always start with a percent and end with a colon.
            There should never be a space inbetween a command and an arg
             */
            char[] command = new char[5];
            received.getChars(0, 5, command, 0);
            //Start read
            if (command[0] != '%') continue;
            if (command[4] != ':') continue;
            StringBuilder query = new StringBuilder();
            //Next three characters
            for (byte i = 1; i <= 3; i++) query.append(command[i]);

            switch (query.toString()) {
                case "MSG" -> {
                    //read the id
                    String id = readID(received);
                    if (id == null) {
                        try {
                            ConnectedConfig connectedConfig = getConnectionViaPort(currentPort);
                            if (connectedConfig == null) continue;
                            connectedConfig.sendMessage("%BAD:");
                            continue;
                        } catch (IOException ignored) {
                            continue;
                        }
                    }
                    //get Config
                    ConnectedConfig config = getConnectionViaID(id);
                    if (config == null) {
                        try {
                            ConnectedConfig connectedConfig = getConnectionViaPort(currentPort);
                            if (connectedConfig == null) continue;
                            connectedConfig.sendMessage("%BAD:");
                            continue;
                        } catch (IOException ignored) {
                            continue;
                        }
                    }
                    //verify sender
                    if (!verifySender(config, id)){
                        try {
                            config.sendMessage("%VER:");
                        } catch (IOException e) {
                            System.out.println("[SERVER] COULD NOT VERIFY CLIENT " + config.getScreenName() + " ON UUID " + config.getUniqueIdentifier());
                            continue;
                        }
                    }

                    //we know who sent it and have their config. Now we need to broadcast it out to all other peers
                    String payload = getPayload(received);

                    logMessageToChannel(payload, config.getChannel());
                    config.checkIn();
                    try {
                        distributeMessageToChannel(payload, config.getChannel(), config.getScreenName());
                    } catch (IOException ignored) {
                        System.out.println("[SERVER] COULD NOT BROADCAST MSG TO CHANNEL " + config.getChannel());
                    }
                }
                case "CON" -> {
                    if (getConnectionViaPort(currentPort) == null) {
                        try {
                            ConnectedConfig connectedConfig = new ConnectedConfig(currentAddress, currentPort);
                            connectedConfig.sendMessage("%YES:id=" + connectedConfig.getUniqueIdentifier());
                            String name = getValueAfterCommand(received);
                            if (name == null || name.equals("SERVER")) name = "ANON-" + connectedConfig.getUniqueIdentifier();
                            connectedConfig.checkIn();
                            connectedConfig.setChannel("1");
                            connectedConfig.setScreenName(name);
                            currentConnections.add(connectedConfig);
                            distributeMessageToChannel(connectedConfig.getScreenName()+ " has joined this channel (" + connectedConfig.getChannel() + ")", connectedConfig.getChannel(), "SERVER");
                        } catch (IOException ignored) {
                            System.out.println("[SERVER] COULD NOT CONNECT USER TO CHANNEL");
                        }
                    }
                }
                case "DIS" -> {
                    String id = readID(received);
                    if (id == null) {
                        try {
                            ConnectedConfig connectedConfig = getConnectionViaPort(currentPort);
                            if (connectedConfig == null) continue;
                            connectedConfig.sendMessage("%BAD:");
                            continue;
                        } catch (IOException ignored) {
                            continue;
                        }
                    }
                    ConnectedConfig config = getConnectionViaID(id);
                    if (config == null) {
                        try {
                            ConnectedConfig connectedConfig = getConnectionViaPort(currentPort);
                            if (connectedConfig == null) continue;
                            connectedConfig.sendMessage("%BAD:");
                            continue;
                        } catch (IOException ignored) {
                            continue;
                        }
                    }

                    if (verifySender(config, id)){
                        currentConnections.remove(config);
                        try {
                            config.sendMessage("%END:");
                            distributeMessageToChannel(config.getScreenName()+ " has disconnected", config.getChannel(),"SERVER");
                        } catch (IOException e) {
                            System.out.println("[SERVER] COULD NOT SEND END TO CLIENT " + config.getScreenName() + " ON UUID " + config.getUniqueIdentifier());
                        }
                    } else {
                        try {
                            config.sendMessage("%VER:");
                        } catch (IOException ignored) {
                            System.out.println("[SERVER] COULD NOT VERIFY CLIENT " + config.getScreenName() + " ON UUID " + config.getUniqueIdentifier());
                        }
                    }

                }
                case "SET" -> {
                    //get id
                    String id = readID(received);
                    if (id == null) {
                        try {
                            ConnectedConfig connectedConfig = getConnectionViaPort(currentPort);
                            if (connectedConfig == null) continue;
                            connectedConfig.sendMessage("%BAD:");
                            continue;
                        } catch (IOException ignored) {
                            continue;
                        }
                    }
                    //get Config
                    ConnectedConfig config = getConnectionViaID(id);
                    if (config == null) {
                        try {
                            ConnectedConfig connectedConfig = getConnectionViaPort(currentPort);
                            if (connectedConfig == null) continue;
                            connectedConfig.sendMessage("%BAD:");
                            continue;
                        } catch (IOException ignored) {
                            continue;
                        }
                    }
                    //verify sender
                    if (!verifySender(config, id)){
                        try {
                            config.sendMessage("%VER:");
                        } catch (IOException e) {
                            System.out.println("[SERVER] COULD NOT VERIFY CLIENT " + config.getScreenName() + " ON UUID " + config.getUniqueIdentifier());
                            continue;
                        }
                    }
                    //determine kind of set
                    String payload = getPayload(received);
                    String type = getPayloadCommand(payload);
                    switch (type){
                        //Change channel
                        case "ch" -> {
                            String prevChannel = config.getChannel();
                            String channel = getPayloadNotCommand(received);
                            config.setChannel(channel);
                            try {
                                config.sendMessage("%YES:Channel changed to \"" + channel + "\"" );
                            } catch (IOException ignored) {
                                System.out.println("[SERVER] COULD NOT SEND YES TO CLIENT " + config.getScreenName() + " ON UUID " + config.getUniqueIdentifier());
                            }
                            try {
                                distributeMessageToChannel(config.getScreenName() + " has left the channel", prevChannel, "SERVER");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        case "nm" -> {
                            String prevname = config.getScreenName();
                            String name = getPayloadNotCommand(received);
                            config.setScreenName(name);
                            try {
                                config.sendMessage("%YES:Name changed to \"" + name + "\"" );
                            } catch (IOException ignored) {
                                System.out.println("[SERVER] COULD NOT SEND YES TO CLIENT " + config.getScreenName() + " ON UUID " + config.getUniqueIdentifier());
                            }
                            try {
                                distributeMessageToChannel(prevname + " has changed their name to " + name, config.getChannel(),"SERVER");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
                /*case "REQ" -> {
                }

                 */
                case "KAL" -> {
                    //get id
                    String id = readID(received);
                    if (id == null) {
                        try {
                            ConnectedConfig connectedConfig = getConnectionViaPort(currentPort);
                            if (connectedConfig == null) continue;
                            connectedConfig.sendMessage("%BAD:");
                            continue;
                        } catch (IOException ignored) {
                            continue;
                        }
                    }
                    //get Config
                    ConnectedConfig config = getConnectionViaID(id);
                    if (config == null) {
                        try {
                            ConnectedConfig connectedConfig = getConnectionViaPort(currentPort);
                            if (connectedConfig == null) continue;
                            connectedConfig.sendMessage("%BAD:");
                            continue;
                        } catch (IOException ignored) {
                            continue;
                        }
                    }
                    //verify sender
                    if (!verifySender(config, id)){
                        try {
                            config.sendMessage("%VER:");
                        } catch (IOException e) {
                            System.out.println("[SERVER] COULD NOT VERIFY CLIENT " + config.getScreenName() + " ON UUID " + config.getUniqueIdentifier());
                            continue;
                        }
                    }
                    //Keepalive
                    config.checkIn();
                    try {
                        config.sendMessage("%YES:");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                default -> System.out.println("[SERVER] COMMAND NOT RECOGNIZED: "+received);
            }
        }
        socket.close();
    }

    private boolean verifySender(ConnectedConfig config, String id){
        return (currentPort == config.getPort() && currentAddress.equals(config.getAddress()) && id.equals(config.getUniqueIdentifier()));
    }

    private String getTime(){
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        Date dt = new Date();
        return sdf.format(dt);
    }

    private void logMessageToChannel(String message, String channel){
        if (!channelHistory.containsKey(channel)) channelHistory.put(channel, new LinkedHashMap<>());
        LinkedHashMap<Long, String> history = channelHistory.get(channel);
        //we want the keys to reflect counting up, where no key is the same. If we get the total number and add one to it then we should have a unique number as an id
        long id = history.size() + 1;
        history.put(id, message);
    }

    private String getMessageFromChannel(String channel, long id){
        if (!channelHistory.containsKey(channel)) return null;
        LinkedHashMap<Long, String> history = channelHistory.get(channel);
        if(!history.containsKey(id)) return null;
        return history.getOrDefault(id, null);
    }

    protected static boolean checkForDuplicateIDs(String uid){
        for (ConnectedConfig connectedConfig : currentConnections) {
            if (connectedConfig.getUniqueIdentifier().equals(uid)) return true;
        }
        return false;
    }

    private void distributeMessageToChannel(String message, String channel, String senderScreenName) throws IOException {
        for (ConnectedConfig connectedConfig : currentConnections){
            if (connectedConfig.getChannel().equals(channel)){
                try {
                    connectedConfig.sendMessage("%MSG:[" + getTime() + "][" + senderScreenName + "]: " + message);
                } catch (Exception ignored) {}
            }
        }
    }
    private ConnectedConfig getConnectionViaID(String uid){
        for (ConnectedConfig connection : currentConnections){
            if (connection.getUniqueIdentifier().equals(uid)) return connection;
        }
        return null;
    }

    private ConnectedConfig getConnectionViaPort(int port){
        for (ConnectedConfig connection : currentConnections){
            if (connection.getPort() == port) return connection;
        }
        return null;
    }

    public static String readID(String input){
        char[] pattern = {':', 'i', 'd', '='};
        char[] buffer = input.toCharArray();
        if (buffer.length < 14) return null;
        byte i = 4;
        for (char c : pattern){
            if (buffer[i] != c) return null;
            i++;
        }
        StringBuilder out = new StringBuilder();
        for (; i <= 13; i++){
            out.append(buffer[i]);
        }
        return out.toString();
    }

    public static String getValueAfterCommand(String input){
        if (input.length() <= 5) return null;
        return String.copyValueOf(input.toCharArray(), 5, input.length()-5);
    }

    public static String getPayloadCommand(String input){
        return String.copyValueOf(input.toCharArray(), 0, 2);
    }

    public static String getPayload(String input){
        return String.valueOf(Arrays.copyOfRange(input.toCharArray(), 14, input.length()));
    }

    public static String getPayloadNotCommand(String input){
        return String.valueOf(Arrays.copyOfRange(input.toCharArray(), 16, input.length()));
    }

}

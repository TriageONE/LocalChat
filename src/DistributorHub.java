import com.sun.source.doctree.SystemPropertyTree;

import java.io.IOException;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class DistributorHub extends Thread {
    protected static DatagramSocket socket;
    private static final Vector<ConnectedConfig> currentConnections = new Vector<>();
    private final LinkedHashMap<String, LinkedHashMap<Long, String>> channelHistory = new LinkedHashMap<>();


    private InetAddress currentAddress;
    private int currentPort;

    public DistributorHub(int port) throws SocketException{
        socket = new DatagramSocket(port);
    }


    public void run() {
        boolean running = true;

        channelHistory.put("1", new LinkedHashMap<>());

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
            received.getChars(0, 4, command, 0);
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

                    //Obtain the time
                    String S = getTime();
                    String outputMessage = "["+S+"]["+config.getScreenName()+"]: " + payload;

                    logMessageToChannel(outputMessage, config.getChannel());
                    config.checkIn();
                    try {
                        distributeMessageToChannel(outputMessage, config.getChannel());
                    } catch (IOException ignored) {
                        System.out.println("[SERVER] COULD NOT BROADCAST MSG TO CHANNEL " + config.getChannel());
                    }
                }
                case "CON" -> {
                    if (getConnectionViaPort(currentPort) == null) {
                        try {
                            ConnectedConfig connectedConfig = new ConnectedConfig(currentAddress, currentPort);
                            connectedConfig.sendMessage("%YES:");
                            currentConnections.add(connectedConfig);
                            connectedConfig.checkIn();
                        } catch (IOException ignored) {

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
                    //change channel
                    String prevChannel = config.getChannel();
                    config.setChannel(getPayload(received));
                    try {
                        config.sendMessage("%YES:");
                    } catch (IOException ignored) {
                        System.out.println("[SERVER] COULD NOT SEND YES TO CLIENT " + config.getScreenName() + " ON UUID " + config.getUniqueIdentifier());
                    }
                    try {
                        distributeMessageToChannel("["+getTime()+"][SERVER]: " + config.getScreenName() + " has left the channel", prevChannel);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
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
                }
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
            if (connectedConfig.getUniqueIdentifier().equals(uid)) return false;
        }
        return true;
    }

    private void distributeMessageToChannel(String message, String channel) throws IOException {
        for (ConnectedConfig connectedConfig : currentConnections){
            if (connectedConfig.getChannel().equals(channel)){
                try {
                    connectedConfig.sendMessage(message);
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

    private String readID(String input){
        char[] pattern = {':', 'i', 'd', '='};
        char[] buffer = input.toCharArray();
        byte i = 4;
        for (char c : pattern){
            if (buffer[i] != c) return null;
            i++;
        }
        StringBuilder out = new StringBuilder();
        for (; i <= 14; i++){
            out.append(buffer[i]);
        }
        return out.toString();
    }

    private String readName(String input){
        char[] pattern = {':', 'n', 'm', '='};
        char[] buffer = input.toCharArray();
        byte i = 4;
        for (char c : pattern){
            if (buffer[i] != c) return null;
            i++;
        }
        StringBuilder out = new StringBuilder();
        for (; i <= input.length() - 1; i++){
            out.append(buffer[i]);
        }
        return out.toString();
    }

    private String getPayload(String input){
        return String.valueOf(Arrays.copyOfRange(input.toCharArray(), 15, input.length()-1));
    }

}

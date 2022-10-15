import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class ConnectedConfig {
    private InetAddress address;
    private int port;
    private String channel;
    private final String uniqueIdentifier;
    private String screenName;
    private boolean deadPeer;

    private long lastReportIn;

    ConnectedConfig(InetAddress address, int port) throws IOException {
        //When a client connects, they will display to the server an address and a port
        //The client is designed to always use this port and send keepalives periodically.
        //If the client fails 3 keepalives, list as a dead peer and queue for removal

        this.address = address;
        this.port = port;
        //The UUID is decided by the server every time and will not change until this object
        this.uniqueIdentifier = generateUUID();
        //The server will send the client a setup packet and assume the client will know to use the same port every time
        //This sets the UUID of the client, for the client
        this.channel = "1";
    }



    private String generateUUID(){
        char[] uid = new char[6];
        boolean duplicate = true;
        while(duplicate){
            for (int i = 0; i <= 5; i++){
                uid[i] = (char) ((int)(Math.random() * 25) + 65);
            }
            String uuid = String.copyValueOf(uid);
            duplicate = DistributorHub.checkForDuplicateIDs(uuid);
        }
        return String.copyValueOf(uid);
    }

    public void sendMessage(String message) throws IOException {
        byte[] buffer = message.getBytes();
        int len = buffer.length;

        DatagramPacket packet = new DatagramPacket(buffer, len, this.address, this.port);
        DistributorHub.socket.send(packet);
    }

    public void checkIn() {
        this.lastReportIn = System.currentTimeMillis() /1000;
    }

    public long getLastReportIn() {
        return lastReportIn;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getChannel() {
        return channel;
    }

    public void changeChannel(String channel){
        this.channel = channel;
    }

    public InetAddress getAddress() {
        return address;
    }

    public void setAddress(InetAddress address) {
        this.address = address;
    }

    public boolean isDeadPeer() {
        return deadPeer;
    }

    public void setDeadPeer(boolean deadPeer) {
        this.deadPeer = deadPeer;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setScreenName(String screenName) {
        this.screenName = screenName;
    }

    public String getScreenName() {
        return screenName;
    }

    public String getUniqueIdentifier() {
        return uniqueIdentifier;
    }
}


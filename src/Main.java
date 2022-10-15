import java.net.InetAddress;

public class Main {

    public static void main(String[] args) throws Exception {
        //Check if there is a server running or not locally
        //If not, launch a distributor here and run it under this PID
        ChatClient client = new ChatClient(InetAddress.getLoopbackAddress());
        client.start();
    }
}
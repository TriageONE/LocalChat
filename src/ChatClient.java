import javax.imageio.plugins.tiff.TIFFDirectory;
import java.io.IOException;
import java.io.PipedReader;
import java.net.*;
import java.nio.charset.MalformedInputException;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Pattern;

public class ChatClient extends Thread{
    //A screenspace client that can display messages, ingest commands, and output other

    //This is the active screen buffer for the input
    String uid;
    InetAddress serverAddr;
    DatagramSocket serverConnection;
    int timeNotHeardBack = 0;
    static boolean ready = true;

    private static DistributorHub hub;

    ChatClient(InetAddress serverAddr) throws Exception {
        this.serverAddr = serverAddr;
        //Is the server currently running?
        System.out.println("[CLIENT] CONNECTING TO '" + serverAddr);
        if (serverAddr.isLoopbackAddress()){
            System.out.println("[CLIENT] LOOPBACK DETECTED, FINDING SERVER..");
            if (!available(1111)){
                //if its available, we want to run a server on that socket
                System.out.println("[CLIENT] NO SERVER FOUND, STARTING LOCAL..");
                hub = new DistributorHub(1111);
                hub.start();
                System.out.println("[CLIENT] LOCAL SERVER STARTED");
            } else {
                System.out.println("[CLIENT] SERVER FOUND ON LOOPBACK ADDRESS FOR LOCAL COMMUNICATION");

            }
        }
        //Prepare a server connection
        connect();
        //Log in to the server
        System.out.println("[CLIENT] ATTEMPTING LOGIN..");
        sendMessageToServer("%CON:");
        final String[] response = {null};
        new Thread() {
            @Override
            public void run() {
                super.run();
                try {
                    //Blocks until someone calls out to us
                    response[0] = readyRecieveMessage();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }.start();

        int count = 0;
        int tries = 0;
        while (response[0] == null){
            Thread.sleep(20);
            count++;
            if (count >= 100) {
                count = 0;
                if (tries > 3){
                    throw new ConnectException("DID NOT RECEIVE RESPONSE FROM REMOTE SERVER FOR CONNECTION ATTEMPT");
                } else {
                    System.out.println("[SERVER] CONNECTION ATTEMPT " + (tries+1) + "/4");
                    tries++;
                    sendMessageToServer("%CON:");
                }
            }
        }

        if (getCommandResponseOrNull(response[0]) != null) {
            this.uid = DistributorHub.readID(response[0]);
        } else throw new Exception("CANNOT PARSE RESPONSE CODE: " + response[0]);

        //now that we are logged in, we can start listening for messages immediately.

        Thread waiter = new Thread(() -> {
            ready = true;
            while (ready){
                try {
                    String message = readyRecieveMessage();
                    String cmd = getCommandResponseOrNull(message);
                    timeNotHeardBack = 0;
                    if (cmd != null){
                        switch (cmd){
                            case "BAD" -> System.out.println("[CLIENT] MALFORMED PACKET QUERY BACK RECIEVED");
                            case "VER", "NCO" -> {
                                System.out.println("[CLIENT] AUTH FAILED, ATTEMPTING TO RESEND VERIFICATION..");
                                sendMessageToServer("%CON:");
                            }
                            case "YES" -> {
                                var id = DistributorHub.readID(message);
                                if (id != null) {
                                    this.uid = id;
                                    System.out.println("[CLIENT] ID SET " + uid);
                                }
                                else {
                                    String r = DistributorHub.getValueAfterCommand(message);
                                    if (!(r == null||r.equals("")||r.equals(" "))) System.out.println("[CLIENT]: " + DistributorHub.getValueAfterCommand(message));
                                }
                            }
                            case "END" -> ready = false;
                            case "MSG" -> System.out.println(DistributorHub.getValueAfterCommand(message));
                            default -> System.out.println("[CLIENT] UNKNOWN SOL: " + message );
                        }
                    }
                } catch (IOException e) {
                    System.out.println("[CLIENT] EXCEPTION CAUGHT FOR IO GENERAL");
                    ready = false;
                } catch (InterruptedException e) {
                    System.out.println("[CLIENT] SESSION TERMINATED");
                    ready = false;
                }
            }
            System.out.println("[CLIENT] SESSION TERMINATED");
        });
        waiter.start();
        new Thread(() -> {
            while (ready) {
                if (timeNotHeardBack > 20){
                    System.out.println("[CLIENT] CONNECTION TIMED OUT, SERVER NEVER RESPONED. EXITING NOW");
                    ready = false;
                    waiter.interrupt();
                }
                if (timeNotHeardBack % 5 == 1){
                    if (timeNotHeardBack == 11 || timeNotHeardBack == 16) System.out.println("[CLIENT] CONNECTION WARINING: " + timeNotHeardBack/5 + " RETRIES");
                    try {
                        sendMessageToServer("%KAL:id=" + this.uid);
                    } catch (InterruptedException | IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                timeNotHeardBack++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) { System.out.println("[CLIENT] INTERRUPTED MAIN KAL THREAD");}
            }
        }).start();

    }

    /**
     * This run method starts the login process. By default on runtime, we search for a server and attempt to start one if we need to.
     * The port to have listened on is usually 1111.
     */
    @Override
    public void run() {
        super.run();
        while (ready){
            Scanner scanner = new Scanner(System.in);
            String c = scanner.nextLine();
            char[] mSplit = c.toCharArray();
            if(mSplit.length == 0) continue;
            if(mSplit[0] == '/'){
                //Start command execution
                //commands are only 3 chars long, always. we have 2 commands, one for name and one for channel

                StringBuilder cmd = new StringBuilder();

                int pointer = 1;
                List<Character> terminators = Arrays.asList(' ', '\0');
                while (!terminators.contains(mSplit[pointer])){
                    cmd.append(mSplit[pointer]);
                    pointer++;
                    if (pointer >= mSplit.length) break;
                }
                String argument = "";
                if (!(mSplit.length >= pointer)){
                    pointer++;
                    StringBuilder arg = new StringBuilder();

                    while (!terminators.contains(mSplit[pointer])){
                        arg.append(mSplit[pointer]);
                        pointer++;
                        if (pointer >= mSplit.length) break;
                    }
                    argument = arg.toString();
                    if (argument.length() > 16 ){
                        argument = argument.substring(0, 16);
                    }
                }

                switch (cmd.toString()) {
                    case "name" -> {
                        try {
                            //server must recieve a string consisting of the command, id, and name at the end
                            //since the uid is constant unchanging length, we can obtain a payload and parse that instead
                            sendMessageToServer("%SET:id=" + this.uid + "nm" + argument);
                        } catch (InterruptedException | IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    case "channel" -> {
                        try {
                            //server must recieve a string consisting of the command, id, and name at the end
                            //since the uid is constant unchanging length, we can obtain a payload and parse that instead

                            sendMessageToServer("%SET:id=" + this.uid + "ch" + argument);
                        } catch (InterruptedException | IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    case "exit" -> {
                        try {
                            sendMessageToServer("%DIS:id=" + this.uid);
                        } catch (InterruptedException | IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            else {
                try {
                    sendMessageToServer("%MSG:id=" + this.uid + c);
                } catch (InterruptedException | IOException e) {
                    throw new RuntimeException(e);
                }
            }
            //Clear the line below

        }
        System.out.println("[CLIENT] NO LONGER READING INPUT");

        //Start the scanner so that we can input stuff
    }

    private String getCommandResponseOrNull(String input){
        char[] arr = input.toCharArray();
        if (arr.length < 5) return null;
        if (arr[0] != '%' && arr[3] != ':') return null;
        List<String> validCommands = Arrays.asList("YES", "BAD", "NCO", "VER", "END", "MSG");
        String command = String.copyValueOf(arr, 1, 3);
        if (validCommands.contains(command)) return command; else return null;
    }

    private int retries = 0;

    private void sendMessageToServer(String message) throws InterruptedException, IOException {
        if (this.serverConnection == null){
            try{ connect();}
            catch (Exception ignored){
                if(retries > 3) throw new RuntimeException("Could not connect to server to send message after 3 retires");
                System.out.println("[CLIENT]: CONNECTING... " + retries + "/4");
                Thread.sleep(3000);
                sendMessageToServer(message);
            }
        }
        byte[] mChar = message.getBytes();
        DatagramPacket packet = new DatagramPacket(mChar, mChar.length, serverAddr, 1111);
        serverConnection.send(packet);
    }

    private String readyRecieveMessage() throws IOException {
        byte[] buf = new byte[256];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        serverConnection.receive(packet);
        String received = new String(packet.getData(), 0, packet.getLength());
        return received.trim();
    }

    private void connect(){
        try {
            System.out.println("[CLIENT] BINDING TO LOCAL SOCKET FOR TRANSMISSION..");
            this.serverConnection = new DatagramSocket();
            retries = 0;
            System.out.println("[CLIENT] TRANSMISSION BIND COMPLETE");
        } catch (Exception ignored) {
            System.out.println("[CLIENT] COULD NOT BIND TO WILDCARD");
            retries++;
        }
    }



    /**
     * When we want to connect to the client terminal interface, we have to call hook() to hook into the client interface
     * and turn the screen to the blocking capture of the client.
     */
    public void hook(){

    }

    /**
     * Checks to see if a specific port is available.
     *
     * @param port the port to check for availability
     */
    private static boolean available(int port) {
        try (DatagramSocket ignored = new DatagramSocket(port)) {
            return false;
        } catch (IOException ignored) {
            return true;
        }
    }


}

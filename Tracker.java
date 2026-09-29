import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Tracker {
    private int puerto;
    // Estructura de Datos: FileID -> Lista de Objetos TorrentTrack (información del enjambre)
    private Map<String, List<TorrentTrack>> swarmRegistry = new ConcurrentHashMap<>();

    public Tracker(int puerto) {
        this.puerto = puerto;
    }

    public void start() {
        System.out.println("==========================================");
        System.out.println("   TRACKER BITTORRENT INICIADO EN PUERTO: " + puerto);
        System.out.println("==========================================");

        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                // Genera un Hilo por cada conexión concurrente
                new Thread(new TrackerClientHandler(clientSocket, swarmRegistry)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        int puerto = (args.length > 0) ? Integer.parseInt(args[0]) : 5000;
        new Tracker(puerto).start();
    }
}

class TrackerClientHandler implements Runnable {
    private Socket socket;
    private Map<String, List<TorrentTrack>> swarmRegistry;

    public TrackerClientHandler(Socket socket, Map<String, List<TorrentTrack>> swarmRegistry) {
        this.socket = socket;
        this.swarmRegistry = swarmRegistry;
    }

    @Override
    public void run() {
        try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            Message request = (Message) in.readObject();

            if (request.getType() == Message.Type.ANNOUNCE || request.getType() == Message.Type.STATUS_UPDATE) {
                String fileId = request.getFileId();
                String peerAddr = request.getPeerIp() + ":" + request.getPeerPort();

                swarmRegistry.computeIfAbsent(fileId, k -> Collections.synchronizedList(new ArrayList<>()));
                List<TorrentTrack> tracks = swarmRegistry.get(fileId);

                boolean found = false;
                synchronized (tracks) {
                    for (TorrentTrack t : tracks) {
                        if (t.getPeerAddress().equals(peerAddr)) {
                            t.setProgress(request.getPieceIndex()); // Se usa el índice/progreso recibido
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        boolean isSeeder = (request.getPieceIndex() >= 100);
                        tracks.add(new TorrentTrack(peerAddr, fileId, request.getPieceIndex(), isSeeder));
                    }
                }

                System.out.println("[TRACKER] Actualización de " + peerAddr + " | Archivo: " + fileId + " | Progreso: " + request.getPieceIndex() + "%");
                out.writeObject("OK");

            } else if (request.getType() == Message.Type.REQUEST_PEERS) {
                List<TorrentTrack> tracks = swarmRegistry.getOrDefault(request.getFileId(), new ArrayList<>());
                List<String> peerList = new ArrayList<>();
                synchronized (tracks) {
                    for (TorrentTrack t : tracks) {
                        // Excluimos la propia dirección del que consulta
                        String addr = t.getPeerAddress();
                        if (!addr.equals(request.getPeerIp() + ":" + request.getPeerPort())) {
                            peerList.add(addr);
                        }
                    }
                }
                out.writeObject(peerList);
            }

        } catch (Exception e) {
            // Manejo silencioso de desconexión abrupta del peer
        }
    }
}
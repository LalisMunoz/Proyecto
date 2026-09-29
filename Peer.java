import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Peer {
    private int localPort;
    private String defaultTrackerIp;
    private int defaultTrackerPort;
    private String myPublicIp;
    
    // Almacena todos los archivos activos gestionados simultáneamente por este nodo
    private Map<String, TorrentHandler> activeTorrents = new ConcurrentHashMap<>();

    public Peer(int localPort, String defaultTrackerIp, int defaultTrackerPort) {
        this.localPort = localPort;
        this.defaultTrackerIp = defaultTrackerIp;
        this.defaultTrackerPort = defaultTrackerPort;
        this.myPublicIp = getLocalNetworkIp(defaultTrackerIp, defaultTrackerPort);
    }

    private String getLocalNetworkIp(String targetIp, int targetPort) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetIp, targetPort), 2000);
            return socket.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception ex) {
                return "127.0.0.1";
            }
        }
    }

    public void addTorrent(String torrentPath, String targetTrackerIp, int targetTrackerPort) throws Exception {
        Torrent torrent = new Torrent(torrentPath);
        
        String trackerToUse = (targetTrackerIp != null && !targetTrackerIp.trim().isEmpty()) ? targetTrackerIp : torrent.tracker;
        int portToUse = (targetTrackerPort > 0) ? targetTrackerPort : torrent.puertoTracker;

        TorrentHandler handler = new TorrentHandler(torrent, localPort, trackerToUse, portToUse, myPublicIp);
        activeTorrents.put(torrent.id, handler);
        new Thread(handler::startDownloadManager).start();
    }

    public void start() {
        // Hilo Servidor P2P para atender peticiones de bloques de otros nodos
        new Thread(this::listenForPeerConnections).start();
        System.out.println("[PEER] Servidor P2P activo en " + myPublicIp + ":" + localPort);
    }

    private void listenForPeerConnections() {
        try (ServerSocket serverSocket = new ServerSocket(localPort)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleIncomingPeer(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("[PEER ERROR] Socket servidor: " + e.getMessage());
        }
    }

    private void handleIncomingPeer(Socket socket) {
        try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            Message request = (Message) in.readObject();
            if (request.getType() == Message.Type.REQUEST_PIECE) {
                String fileId = request.getFileId();
                TorrentHandler handler = activeTorrents.get(fileId);
                
                if (handler != null && handler.hasPiece(request.getPieceIndex())) {
                    byte[] chunk = handler.readPieceFromFile(request.getPieceIndex());
                    out.writeObject(new Message(Message.Type.PIECE_DATA, fileId, request.getPieceIndex(), chunk, myPublicIp, localPort));
                }
            }
        } catch (Exception e) {
            // Manejo silencioso de desconexiones
        }
    }

    // --- MÉTODOS DEL MENÚ INTERACTIVO ---

    public void mostrarEstadoRed() {
        System.out.println("\n==========================================");
        System.out.println("          ESTADO DE LA RED P2P            ");
        System.out.println("==========================================");
        System.out.println("Leyenda: Nodos activos conectados transfiriendo archivos");
        System.out.println("Nodo local (Tú)  : " + myPublicIp + ":" + localPort);
        System.out.println("Tracker por defecto: " + defaultTrackerIp + ":" + defaultTrackerPort);
        System.out.println("Archivos en gestión : " + activeTorrents.size());
        System.out.println("------------------------------------------\n");
    }

    public void consultarProcesoDescarga() {
        System.out.println("\n==========================================");
        System.out.println("     CONSULTA DE PROGRESO DE DESCARGA     ");
        System.out.println("==========================================");
        if (activeTorrents.isEmpty()) {
            System.out.println("No hay archivos activos en transferencia.");
        } else {
            activeTorrents.forEach((id, handler) -> {
                double pct = handler.getProgresoActual();
                String estado = (pct >= 100.0) ? "COMPLETADO (SEEDER)" : "DESCARGANDO (LEECHER)";
                System.out.printf("Archivo: %-15s | Tracker: %s:%d | Progreso: %6.2f%% | Estado: %s\n", 
                        handler.getNombreArchivo(), handler.getTrackerIp(), handler.getTrackerPort(), pct, estado);
            });
        }
        System.out.println("------------------------------------------\n");
    }

    // --- MANEJADOR INDIVIDUAL DE CADA TORRENT EN SEGUNDO PLANO ---

    private static class TorrentHandler {
        private Torrent torrent;
        private File sourceFile;
        private BitSet downloadedPieces;
        private int localPort;
        private String trackerIp;
        private int trackerPort;
        private String myPublicIp;

        public TorrentHandler(Torrent torrent, int localPort, String trackerIp, int trackerPort, String myPublicIp) {
            this.torrent = torrent;
            this.localPort = localPort;
            this.trackerIp = trackerIp;
            this.trackerPort = trackerPort;
            this.myPublicIp = myPublicIp;
            this.sourceFile = new File(torrent.archivo);
            this.downloadedPieces = new BitSet(torrent.pedazos);
            cargarEstadoPrevio();
        }

        public String getNombreArchivo() { return torrent.nombre; }
        public String getTrackerIp() { return trackerIp; }
        public int getTrackerPort() { return trackerPort; }

        public double getProgresoActual() {
            return (downloadedPieces.cardinality() * 100.0) / torrent.pedazos;
        }

        private void cargarEstadoPrevio() {
            if (sourceFile.exists() && sourceFile.length() > 0) {
                try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
                    for (int i = 0; i < torrent.pedazos; i++) {
                        int offset = i * Torrent.PEDAZO_TAM;
                        int length = (i == torrent.pedazos - 1) ? torrent.ultimo_pedazo : Torrent.PEDAZO_TAM;
                        if (raf.length() >= offset + length) {
                            byte[] buffer = new byte[length];
                            raf.seek(offset);
                            raf.readFully(buffer);
                            if (torrent.isPieceValid(buffer, i)) {
                                downloadedPieces.set(i);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[" + torrent.nombre + "] Escaneando bloques previos...");
                }
            }
            int obtenidas = downloadedPieces.cardinality();
            double pct = (obtenidas * 100.0) / torrent.pedazos;
            System.out.println("[" + torrent.nombre + "] Avance local: " + obtenidas + "/" + torrent.pedazos + " (" + String.format("%.2f", pct) + "%)");
        }

        public boolean hasPiece(int index) {
            return downloadedPieces.get(index);
        }

        public synchronized byte[] readPieceFromFile(int index) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
                int offset = index * Torrent.PEDAZO_TAM;
                int length = (index == torrent.pedazos - 1) ? torrent.ultimo_pedazo : Torrent.PEDAZO_TAM;
                byte[] data = new byte[length];
                raf.seek(offset);
                raf.readFully(data);
                return data;
            }
        }

        private synchronized void writePieceToFile(int index, byte[] data) throws IOException {
            File parent = sourceFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "rw")) {
                int offset = index * Torrent.PEDAZO_TAM;
                raf.seek(offset);
                raf.write(data);
                downloadedPieces.set(index);
            }
        }

        public void startDownloadManager() {
            reportStatusToTracker((int) getProgresoActual());

            while (downloadedPieces.cardinality() < torrent.pedazos) {
                try {
                    List<String> peers = getPeersFromTracker();
                    for (int i = 0; i < torrent.pedazos; i++) {
                        if (!downloadedPieces.get(i)) {
                            for (String peerAddr : peers) {
                                String[] parts = peerAddr.split(":");
                                if (downloadPieceFromPeer(parts[0], Integer.parseInt(parts[1]), i)) {
                                    reportStatusToTracker((int) getProgresoActual());
                                    break;
                                }
                            }
                        }
                    }
                    Thread.sleep(1500);
                } catch (Exception e) {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                }
            }

            reportStatusToTracker(100);
        }

        private List<String> getPeersFromTracker() throws Exception {
            try (Socket socket = new Socket(trackerIp, trackerPort);
                 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                out.writeObject(new Message(Message.Type.REQUEST_PEERS, torrent.id, 0, null, myPublicIp, localPort));
                return (List<String>) in.readObject();
            }
        }

        private boolean downloadPieceFromPeer(String ip, int port, int pieceIndex) {
            try (Socket socket = new Socket(ip, port);
                 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                socket.setSoTimeout(3000);
                out.writeObject(new Message(Message.Type.REQUEST_PIECE, torrent.id, pieceIndex, null, myPublicIp, localPort));
                Message response = (Message) in.readObject();

                if (response != null && response.getData() != null) {
                    if (torrent.isPieceValid(response.getData(), pieceIndex)) {
                        writePieceToFile(pieceIndex, response.getData());
                        return true;
                    }
                }
            } catch (Exception ignored) {}
            return false;
        }

        private void reportStatusToTracker(int progress) {
            try (Socket socket = new Socket(trackerIp, trackerPort);
                 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                out.writeObject(new Message(Message.Type.ANNOUNCE, torrent.id, progress, null, myPublicIp, localPort));
                in.readObject();
            } catch (Exception ignored) {}
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Uso: java -cp \"json-20140107.jar;.\" Peer <puerto_local> <ip_tracker> <puerto_tracker>");
            return;
        }

        int localPort = Integer.parseInt(args[0]);
        String defaultTrackerIp = args[1];
        int defaultTrackerPort = Integer.parseInt(args[2]);

        Peer peer = new Peer(localPort, defaultTrackerIp, defaultTrackerPort);
        peer.start();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("\n========== MENÚ BITTORRENT P2P ==========");
            System.out.println("1) Transferir archivo");
            System.out.println("2) Mostrar estado de la red");
            System.out.println("3) Consultar proceso de descarga");
            System.out.println("4) Salir");
            System.out.print("Seleccione una opción: ");

            String opcion = scanner.nextLine().trim();
            switch (opcion) {
                case "1":
                    System.out.print("Ingrese la IP del Tracker destino: ");
                    String targetIp = scanner.nextLine().trim();
                    
                    System.out.print("Ingrese el puerto del Tracker (ej. 5000): ");
                    int targetPort = Integer.parseInt(scanner.nextLine().trim());

                    System.out.print("Ingrese la ruta del archivo .torrent: ");
                    String rutaTorrent = scanner.nextLine().trim();
                    
                    try {
                        peer.addTorrent(rutaTorrent, targetIp, targetPort);
                        System.out.println("-> Añadida transferencia en segundo plano hacia " + targetIp + ":" + targetPort);
                    } catch (Exception e) {
                        System.out.println("Error al procesar el torrent: " + e.getMessage());
                    }
                    break;
                case "2":
                    peer.mostrarEstadoRed();
                    break;
                case "3":
                    peer.consultarProcesoDescarga();
                    break;
                case "4":
                    System.out.println("Saliendo del programa...");
                    System.exit(0);
                    break;
                default:
                    System.out.println("Opción no válida. Intente de nuevo.");
            }
        }
    }
}
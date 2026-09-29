import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        ANNOUNCE,        // Peer se registra con el Tracker
        REQUEST_PEERS,   // Peer solicita lista de peers al Tracker
        PEER_LIST,       // Tracker responde con la lista de peers
        REQUEST_PIECE,   // Peer solicita una pieza a otro Peer
        PIECE_DATA,      // Peer envía los bytes de la pieza solicitada
        STATUS_UPDATE    // Peer reporta su porcentaje de avance al Tracker
    }

    private Type type;
    private String fileId;
    private int pieceIndex;
    private byte[] data;
    private String peerIp;
    private int peerPort;

    // Constructor para mensajes del protocolo
    public Message(Type type, String fileId, int pieceIndex, byte[] data, String peerIp, int peerPort) {
        this.type = type;
        this.fileId = fileId;
        this.pieceIndex = pieceIndex;
        this.data = data;
        this.peerIp = peerIp;
        this.peerPort = peerPort;
    }

    public Type getType() { return type; }
    public String getFileId() { return fileId; }
    public int getPieceIndex() { return pieceIndex; }
    public byte[] getData() { return data; }
    public String getPeerIp() { return peerIp; }
    public int getPeerPort() { return peerPort; }
}
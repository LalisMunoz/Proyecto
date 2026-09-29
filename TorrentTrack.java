import java.io.Serializable;

public class TorrentTrack implements Serializable {
    private String peerAddress; // "IP:Puerto"
    private String fileId;
    private double progress;
    private boolean isSeeder;

    public TorrentTrack(String peerAddress, String fileId, double progress, boolean isSeeder) {
        this.peerAddress = peerAddress;
        this.fileId = fileId;
        this.progress = progress;
        this.isSeeder = isSeeder;
    }

    public String getPeerAddress() { return peerAddress; }
    public String getFileId() { return fileId; }
    public double getProgress() { return progress; }
    public boolean isSeeder() { return isSeeder; }
    
    public void setProgress(double progress) {
        this.progress = progress;
        if (progress >= 100.0) {
            this.isSeeder = true;
        }
    }
}
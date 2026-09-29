import java.io.*;
import java.security.*;
import java.math.BigInteger;
import org.json.JSONObject;
import org.json.JSONArray;

public class Torrent {
    
    // Tamaño predeterminado del fragmento en bytes (100 KB)
    static final int PEDAZO_TAM = 102400; 
    static final int MAX_PETICION = 10;
    static final String HASH_ALGORITHM = "MD5";

    // Atributos del metadato .torrent
    String id;
    String tracker;
    int puertoTracker;
    int pedazos;
    int ultimo_pedazo;
    String nombre;
    String archivo;
    Boolean[] obtenidos;
    String[] checksum;
    
    /**
     * Constructor para parsear/leer un archivo .torrent existente
     */
    public Torrent(String torrentPath) throws IOException {
        File file = new File(torrentPath);
        FileReader fr = new FileReader(file);
        BufferedReader br = new BufferedReader(fr);
        String line = br.readLine();
        
        if (line != null) {
            JSONObject obj = new JSONObject(line);
            id = obj.getString("id");
            tracker = obj.getString("tracker");
            puertoTracker = obj.getInt("puertoTracker");
            pedazos = obj.getInt("pieces");
            ultimo_pedazo = obj.getInt("lastPiece");
            nombre = obj.getString("name"); 
            archivo = obj.getString("filepath");
            
            // Cargar los checksums MD5 de cada fragmento
            JSONArray checksumJSON = obj.getJSONArray("checksum");
            checksum = new String[checksumJSON.length()];
            for (int i = 0, l = checksumJSON.length(); i < l; i++) {
                checksum[i] = checksumJSON.getString(i);
            }
        }
        br.close();
        fr.close();
        obtenidos = new Boolean[pedazos];
    }

    /**
     * Valida si un fragmento recibido coincide con su firma MD5 esperada
     */
    public Boolean isPieceValid(byte[] piece, int index) {
        try { 
            MessageDigest m = MessageDigest.getInstance(HASH_ALGORITHM);
            return hash(m, piece).equals(checksum[index]);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Generador de hashes MD5
     */
    public static String hash(MessageDigest messageDigest, byte[] data) throws NoSuchAlgorithmException {
        messageDigest.reset();
        messageDigest.update(data);
        byte[] digest = messageDigest.digest();
        BigInteger bigInt = new BigInteger(1, digest);
        String hashtext = bigInt.toString(16);
        while (hashtext.length() < 32) {
            hashtext = "0" + hashtext;
        }
        return hashtext;
    }

    /**
     * Método principal para crear un nuevo archivo .torrent
     */
    public static void main(String args[]) throws NoSuchAlgorithmException {
        if (args.length == 3) {
            try { 
                MessageDigest m = MessageDigest.getInstance(HASH_ALGORITHM);
                String file_path = "archivos/" + args[2];
                File fileObj = new File(file_path);
                
                if (fileObj.exists()) {
                    String fileName = fileObj.getName();
                    int pos = fileName.lastIndexOf(".");
                    if (pos > 0) {
                        fileName = fileName.substring(0, pos);
                    }
                    
                    // Cálculo de piezas totales y del último fragmento
                    double fileSize = fileObj.length();
                    int piecesQty = (int) Math.ceil(fileSize / Torrent.PEDAZO_TAM);
                    int lastPiece = 0;
                    if (piecesQty * Torrent.PEDAZO_TAM > fileSize) {
                        lastPiece = (int) (fileSize - (piecesQty - 1) * Torrent.PEDAZO_TAM);
                    } else {
                        lastPiece = Torrent.PEDAZO_TAM;
                    }

                    // Lectura y generación del checksum MD5 por pedazo
                    JSONArray checksum = new JSONArray();
                    InputStream is = new FileInputStream(file_path);
                    
                    for (int i = 0; i < piecesQty; i++) {
                        byte[] data;
                        if (i < piecesQty - 1) {
                            data = new byte[Torrent.PEDAZO_TAM];
                            is.read(data, 0, Torrent.PEDAZO_TAM);
                        } else {
                            data = new byte[lastPiece];
                            is.read(data, 0, lastPiece);
                        }
                        checksum.put(hash(m, data));
                    }
                    is.close();

                    String ip = args[0];
                    int puertoTracker = Integer.parseInt(args[1]);

                    // Guardar los metadatos estructurados en formato JSON
                    File torrentDir = new File("torrents");
                    if (!torrentDir.exists()) torrentDir.mkdirs();

                    FileOutputStream fos = new FileOutputStream("torrents/" + fileName + ".torrent");
                    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));

                    JSONObject torrentObj = new JSONObject();
                    torrentObj.put("tracker", ip);
                    torrentObj.put("pieces", piecesQty);
                    torrentObj.put("lastPiece", lastPiece);
                    torrentObj.put("filepath", file_path);
                    torrentObj.put("name", fileObj.getName());
                    torrentObj.put("puertoTracker", puertoTracker);
                    torrentObj.put("checksum", checksum);
                    torrentObj.put("id", hash(m, fileName.getBytes()));

                    bw.write(torrentObj.toString());
                    bw.close();
                    fos.close();

                    System.out.println("Torrent creado exitosamente en torrents/" + fileName + ".torrent");
                    return;
                } else {
                    System.out.println("El archivo no existe en el directorio archivos/");
                    return;
                }
            } catch (FileNotFoundException fe) {
                fe.printStackTrace();
            } catch (IOException ie) {
                ie.printStackTrace();
            }
        }
        
        System.out.println("Uso: java -cp json-20140107.jar:. Torrent <tracker_ip> <puerto_tracker> <nombre_archivo>");
    }
}
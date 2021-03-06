package lighthouse.subwindows;

import com.google.common.base.*;
import de.jensd.fx.fontawesome.*;
import javafx.concurrent.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import lighthouse.*;
import lighthouse.protocol.*;
import lighthouse.utils.*;
import lighthouse.wallet.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;

import static com.google.common.base.Preconditions.*;

public class PledgeUploadWindow {
    private static final Logger log = LoggerFactory.getLogger(PledgeUploadWindow.class);

    public Main.OverlayUI overlayUI;

    @FXML public Label uploadLabel;
    @FXML public Label uploadIcon;
    @FXML public ProgressIndicator uploadProgress;
    private Task<Void> uploadTask;

    private Runnable onSuccess;

    public void initialize() {
        AwesomeDude.setIcon(uploadIcon, AwesomeIcon.CLOUD_UPLOAD, "60");
    }

    public static void open(Project project, PledgingWallet.PendingPledge pledge, Runnable onSuccess) {
        Main.OverlayUI<PledgeUploadWindow> window = Main.instance.overlayUI("subwindows/pledge_upload.fxml", "Upload");
        window.controller.startUpload(project, pledge);
        window.controller.onSuccess = onSuccess;
    }

    private void startUpload(Project project, PledgingWallet.PendingPledge pledge) {
        uploadTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    URI url = project.getPaymentURL();
                    checkState(url != null);
                    log.info("Uploading pledge to {}", url);
                    // Tricksy URLs like jar:// result in a crash at this point.
                    HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setConnectTimeout(10000);  // 10 seconds
                    connection.setInstanceFollowRedirects(true);
                    connection.setReadTimeout(10000);
                    try {
                        log.info("Connecting");
                        connection.connect();
                    } catch (IOException e) {
                        log.error("Connect failed: {}", e.getLocalizedMessage());
                        updateMessage("Connection failed: " + e.getLocalizedMessage());
                        throw e;
                    }
                    log.info("Connected");
                    updateMessage("Connected, uploading ...");
                    try (OutputStream stream = connection.getOutputStream()) {
                        final LHProtos.Pledge data = pledge.getData();
                        data.writeTo(stream);
                        log.info("Data uploaded");
                        int response = connection.getResponseCode();
                        if (response != HttpURLConnection.HTTP_OK) {
                            throw new Exception(String.format("Server said %d %s", connection.getResponseCode(), connection.getResponseMessage()));
                        } else {
                            log.info("Server accepted, committing");
                            pledge.commit(false);
                        }
                    }
                    return null;
                } catch (Exception e) {
                    GuiUtils.informationalAlert("Upload failed", "%s. This probably indicates either a problem with " +
                                    "your internet connection or a configuration issue with the server. You could try again later.",
                            Throwables.getRootCause(e).getLocalizedMessage());
                    log.error("Upload error", e);
                    throw e;
                }
            }
        };
        uploadLabel.textProperty().bind(uploadTask.messageProperty());
        uploadTask.setOnSucceeded(ev -> {
            overlayUI.done();
            onSuccess.run();
        });
        uploadTask.setOnFailed(ev -> {
            overlayUI.done();
        });
        Thread thread = new Thread(uploadTask);
        thread.setDaemon(false);
        thread.setName("Pledge upload");
        thread.start();
    }

    @FXML
    public void cancelClicked(ActionEvent event) {
        uploadTask.cancel(true);
        overlayUI.done();
    }
}

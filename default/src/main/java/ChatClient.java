import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

public class ChatClient extends Application {
    private static final int HEADER_LENGTH = 10;
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 1234;

    private DataOutputStream outputStream;
    private DataInputStream inputStream;
    private Socket socket;

    private TextArea chatArea;
    private TextField inputField;
    private String username;
    private Thread connectionThread;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Stage usernameStage = new Stage();
        VBox usernameRoot = new VBox(10);
        usernameRoot.setPrefSize(300, 150);

        Label promptLabel = new Label("Введите ваше имя:");
        TextField usernameField = new TextField();
        usernameField.setPromptText("Ваше имя");

        Button confirmButton = new Button("Подтвердить");
        confirmButton.setOnAction(e -> {
            username = usernameField.getText();
            if (username != null && !username.trim().isEmpty()) {
                usernameStage.close();
                showChatWindow(stage);
                connectionThread = new Thread(this::connectToServer);
                connectionThread.start();
            }
        });

        usernameRoot.getChildren().addAll(promptLabel, usernameField, confirmButton);
        Scene usernameScene = new Scene(usernameRoot);
        usernameStage.setScene(usernameScene);
        usernameStage.setTitle("Введите имя");
        usernameStage.show();

        stage.setOnCloseRequest(event -> {
            event.consume();
            exitApplication();
        });
    }

    private void showChatWindow(Stage stage) {
        VBox root = new VBox(10);
        root.setPrefSize(400, 600);

        // Отображение имени пользователя
        Label usernameLabel = new Label("Вы вошли как: " + username);
        usernameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);

        inputField = new TextField();
        inputField.setPromptText("Введите сообщение...");
        inputField.setOnAction(event -> sendMessage());

        Button sendButton = new Button("Отправить");
        sendButton.setOnAction(event -> sendMessage());

        Button clearChatButton = new Button("Очистить чат");
        clearChatButton.setOnAction(event -> chatArea.clear());

        HBox inputBox = new HBox(10, inputField, sendButton);
        inputBox.setPrefHeight(50);

        root.getChildren().addAll(usernameLabel, chatArea, inputBox, clearChatButton);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle("Chat Client: " + username);
        stage.show();
    }

    private void connectToServer() {
        try {
            socket = new Socket(SERVER_IP, SERVER_PORT);
            outputStream = new DataOutputStream(socket.getOutputStream());
            inputStream = new DataInputStream(socket.getInputStream());

            // Отправка имени пользователя
            sendHeaderMessage(username);

            while (!Thread.currentThread().isInterrupted()) {
                String fullMessage = receiveHeaderMessage();
                if (!fullMessage.contains(":")) {
                    continue;
                }
                String[] parts = fullMessage.split(":", 2);
                String sender = parts[0];
                String message = parts[1];
                appendMessage(sender + " > " + message);
            }
        } catch (IOException e) {
            appendMessage("Ошибка соединения: " + e.getMessage());
        } finally {
            closeConnection();
        }
    }

    private void sendMessage() {
        String message = inputField.getText();
        if (!message.isEmpty()) {
            try {
                sendHeaderMessage(username + ":" + message);
                appendMessage(username + " > " + message);
                inputField.clear();
            } catch (IOException e) {
                appendMessage("Ошибка отправки сообщения: " + e.getMessage());
            }
        }
    }

    private void sendHeaderMessage(String message) throws IOException {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        String header = String.format("%-" + HEADER_LENGTH + "d", messageBytes.length);
        outputStream.write(header.getBytes(StandardCharsets.UTF_8));
        outputStream.write(messageBytes);
        outputStream.flush();
    }

    private String receiveHeaderMessage() throws IOException {
        byte[] headerBytes = new byte[HEADER_LENGTH];
        inputStream.readFully(headerBytes);
        int messageLength = Integer.parseInt(new String(headerBytes).trim());
        byte[] messageBytes = new byte[messageLength];
        inputStream.readFully(messageBytes);
        return new String(messageBytes, StandardCharsets.UTF_8);
    }

    private void appendMessage(String message) {
        Platform.runLater(() -> chatArea.appendText(message + "\n"));
    }

    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
            appendMessage("Ошибка при закрытии соединения: " + e.getMessage());
        }
    }

    private void exitApplication() {
        if (connectionThread != null) {
            connectionThread.interrupt();
        }
        closeConnection();
        Platform.exit();
        System.exit(0);
    }
}

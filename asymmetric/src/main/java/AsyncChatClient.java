import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;

import javax.crypto.Cipher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class AsyncChatClient extends Application {
    //TODO Вынести в параметры запуска
    private static final int HEADER_LENGTH = 10;
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 1234;

    private static final String PUBLIC_KEYS_FILE = "public_keys.txt";

    private DataOutputStream outputStream;
    private DataInputStream inputStream;
    private Socket socket;

    private TextArea chatArea;
    private TextField inputField;
    private TextField recipientField;
    private CheckBox encryptCheckBox;

    private KeyManager keyManager;
    private String username;
    private Thread connectionThread;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        try {
            keyManager = new KeyManager();
        } catch (Exception e) {
            e.printStackTrace();
            Platform.exit();
        }

        // Окно для ввода имени пользователя
        Stage usernameStage = new Stage();
        VBox usernameRoot = new VBox(10);
        usernameRoot.setPrefSize(300, 150);

        Label promptLabel = new Label("Введите ваше имя:");
        TextField usernameField = new TextField();
        usernameField.setPromptText("Ваше имя");

        Button confirmButton = getConfirmationButton(stage, usernameField, usernameStage);


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

    private Button getConfirmationButton(Stage stage, TextField usernameField, Stage usernameStage) {
        Button confirmButton = new Button("Подтвердить");
        confirmButton.setOnAction(help -> {
            username = usernameField.getText();
            if (username != null && !username.trim().isEmpty()) {
                //Очищаем, либо создаём файл, где будут храниться публичные ключи
                clearPublicKeysFile();
                usernameStage.close();
                showChatWindow(stage);
                connectionThread = new Thread(this::connectToServer);
                connectionThread.start();
                appendMessage("Ваш публичный ключ: " + keyManager.exportPublicKey());
            }
        });
        return confirmButton;
    }

    private void showChatWindow(Stage stage) {
        VBox root = new VBox(10);
        root.setPrefSize(400, 600);

        Label usernameLabel = new Label("Вы вошли как: " + username);
        usernameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        // Поле для ввода имени собеседника
        recipientField = new TextField();
        recipientField.setPromptText("Введите имя собеседника");

        // Переключатель для включения шифрования
        encryptCheckBox = new CheckBox("Шифровать сообщения");

        // Кнопка для управления публичными ключами
        Button manageKeysButton = new Button("Управление ключами");
        manageKeysButton.setOnAction(e -> showKeyManagerWindow());

        // Кнопка для просмотра публичного ключа
        Button showKeyButton = new Button("Показать мой публичный ключ");
        showKeyButton.setOnAction(e -> appendMessage("Ваш публичный ключ: " + keyManager.exportPublicKey()));

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

        root.getChildren().addAll(usernameLabel, recipientField, encryptCheckBox, manageKeysButton, showKeyButton,
                chatArea, inputBox, clearChatButton);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle("Async Chat Client: " + username);
        stage.show();
    }

    private void connectToServer() {
        try {
            socket = new Socket(SERVER_IP, SERVER_PORT);
            outputStream = new DataOutputStream(socket.getOutputStream());
            inputStream = new DataInputStream(socket.getInputStream());

            // Сервер сначала получает от кого приходит сообщение
            sendHeaderMessage(username);

            while (!Thread.currentThread().isInterrupted()) {
                String fullMessage = receiveHeaderMessage();

                //Нужно, чтобы пропускать сообщения вида username (которые серверу отправляются, чтобы понять - от
                // кого сообщение)
                if (!fullMessage.contains(":")) {
                    continue;
                }

                String[] parts = fullMessage.split(":", 2);
                String sender = parts[0];
                String message = parts[1];

                // Данная проверка еобходима, чтобы понять в ккой кодровке находится сообщение,
                // Если будет base64, то клиент попробует дешифровать
                if (isBase64(message)) {
                    try {
                        message = "[Зашифровано] > " + decryptMessage(message);
                    } catch (Exception e) {
                    }
                }

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
                if (encryptCheckBox.isSelected()) {
                    String recipient = recipientField.getText();
                    if (recipient.isEmpty()) {
                        appendMessage("Ошибка: Вы не указали получателя");
                        return;
                    }
                    message = encryptMessage(recipient, message);
                }
                // Отправка собственного сообщения
                sendHeaderMessage(username + ":" + message);
                //Вывод собственного сообщения на экран
                appendMessage(username + " > " + inputField.getText());
                inputField.clear();
            } catch (Exception e) {
                appendMessage("Ошибка отправки сообщения: " + e.getMessage());
            }
        }
    }

    private void showKeyManagerWindow() {
        Stage keyStage = new Stage();
        VBox root = new VBox(10);

        Label titleLabel = new Label("Публичные ключи пользователей");

        TextArea keyArea = new TextArea();
        keyArea.setEditable(false);
        loadPublicKeysToTextArea(keyArea);

        TextField usernameField = new TextField();
        usernameField.setPromptText("Имя пользователя");

        TextField publicKeyField = new TextField();
        publicKeyField.setPromptText("Публичный ключ (Base64)");

        Button addKeyButton = getAddPublicKeyButton(usernameField, publicKeyField, keyArea);

        Button clearKeysButton = new Button("Очистить файл ключей");
        clearKeysButton.setOnAction(event -> {
            clearPublicKeysFile();
            keyArea.clear();
            appendMessage("Все публичные ключи удалены");
        });

        root.getChildren().addAll(titleLabel, keyArea, usernameField, publicKeyField, addKeyButton, clearKeysButton);

        Scene scene = new Scene(root, 400, 400);
        keyStage.setScene(scene);
        keyStage.setTitle("Управление ключами");
        keyStage.show();
    }

    private Button getAddPublicKeyButton(TextField usernameField, TextField publicKeyField, TextArea keyArea) {
        Button addKeyButton = new Button("Добавить ключ");
        addKeyButton.setOnAction(event -> {
            String usernameToStore = usernameField.getText();
            String publicKey = publicKeyField.getText();
            try {
                keyManager.addPublicKey(usernameToStore, publicKey);
                savePublicKeyToFile(usernameToStore, publicKey);
                keyArea.appendText("Ключ добавлен для пользователя: " + usernameToStore + "\n");
            } catch (Exception e) {
                keyArea.appendText("Ошибка добавления ключа: " + e.getMessage() + "\n");
            }
            usernameField.clear();
            publicKeyField.clear();
        });
        return addKeyButton;
    }

    private void savePublicKeyToFile(String username, String publicKey) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(this.username + "_" + PUBLIC_KEYS_FILE, true))) {
            writer.write(username + ":" + publicKey);
            writer.newLine();
        } catch (IOException e) {
            appendMessage("Ошибка сохранения ключа: " + e.getMessage());
        }
    }

    private void loadPublicKeysToTextArea(TextArea keyArea) {
        try (BufferedReader reader = new BufferedReader(new FileReader(this.username + "_" + PUBLIC_KEYS_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                keyArea.appendText(line + "\n");
            }
        } catch (IOException e) {
            appendMessage("Ошибка загрузки ключей: " + e.getMessage());
        }
    }

    private void clearPublicKeysFile() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(this.username + "_" + PUBLIC_KEYS_FILE))) {
            writer.write("");
        } catch (IOException e) {
            appendMessage("Ошибка очистки файла ключей: " + e.getMessage());
        }
    }

    private String encryptMessage(String user, String message) throws Exception {
        PublicKey recipientKey = keyManager.getPublicKey(user);
        if (recipientKey == null) {
            throw new Exception("Публичный ключ для пользователя " + user + " не найден");
        }
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, recipientKey);
        byte[] encryptedBytes = cipher.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    private String decryptMessage(String encryptedMessage) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyManager.getPrivateKey());
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedMessage));
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    private boolean isBase64(String message) {
        try {
            Base64.getDecoder().decode(message);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
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
            clearPublicKeysFile();
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
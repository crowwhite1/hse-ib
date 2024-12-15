import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

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

public class SyncChatClient extends Application {
    //TODO Вынести в параметры запуска
    private static final int HEADER_LENGTH = 10;
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 1234;

    private DataOutputStream outputStream;
    private DataInputStream inputStream;
    private Socket socket;

    private TextArea chatArea;
    private TextField inputField;
    private TextField recipientField;
    private CheckBox encryptCheckBox;

    private String username;
    private Thread connectionThread;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
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
            event.consume(); // Отключаем автоматическое закрытие
            exitApplication();
        });
    }

    private Button getConfirmationButton(Stage stage, TextField usernameField, Stage usernameStage) {
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
        encryptCheckBox.setOnAction(event -> {
            if (encryptCheckBox.isSelected() && recipientField.getText() != null && !recipientField.getText().isBlank()) {
                try {
                    loadOrGenerateKey(username, recipientField.getText());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });


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

        root.getChildren().addAll(usernameLabel, recipientField, encryptCheckBox, chatArea, inputBox, clearChatButton);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle("Sync Chat Client: " + username);
        stage.show();
    }

    private void connectToServer() {
        try {
            socket = new Socket(SERVER_IP, SERVER_PORT);
            outputStream = new DataOutputStream(socket.getOutputStream());
            inputStream = new DataInputStream(socket.getInputStream());

            // Отправка имени пользователя
            sendHeaderMessage(username);

            // Получение сообщений
            while (!Thread.currentThread().isInterrupted()) {
                String fullMessage = receiveHeaderMessage();

                // Проверяем, содержит ли сообщение разделитель ":"
                if (!fullMessage.contains(":")) {
                    continue;
                }

                String[] parts = fullMessage.split(":", 2);
                String sender = parts[0];
                String message = parts[1];

                // Проверяем в base64 и есть ли файл для дешифрации
                if (isBase64(message) && hasKeyFor(sender)) {
                    try {
                        message = "[Зашифровано] > " + decryptMessage(sender, message);
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
                // Шифруем сообщение, если есть галочка
                if (encryptCheckBox.isSelected()) {
                    String recipient = recipientField.getText();
                    if (recipient.isEmpty()) {
                        appendMessage("Ошибка: Укажите получателя для шифрования");
                        return;
                    }
                    message = encryptMessage(recipient, message);
                }

                // сообщение в формате "отправитель:сообщение", дабы нормально его обработать
                sendHeaderMessage(username + ":" + message);

                // Отображаем сообщение у нас
                appendMessage(username + " > " + inputField.getText());
                inputField.clear();
            } catch (Exception e) {
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

    private String encryptMessage(String recipient, String message) throws Exception {
        // Загружаем или генерируем ключ
        SecretKey key = loadOrGenerateKey(username, recipient);

        // Инициализация шифрования
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, key);

        // Шифруем сообщение и преобразуем в строку Base64
        byte[] encryptedBytes = cipher.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }


    private String decryptMessage(String sender, String encryptedMessage) throws Exception {
        // Загружаем или генерируем ключ
        SecretKey key = loadOrGenerateKey(username, sender);

        // Декодируем сообщение из Base64
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedMessage);

        // Расшифровываем сообщение
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }


    private SecretKey loadOrGenerateKey(String user1, String user2) throws Exception {
        // Упорядочиваем имена пользователей в алфавитном порядке
        String sortedUsers = user1.compareTo(user2) < 0 ? user1 + "_" + user2 : user2 + "_" + user1;
        File keyFile = new File("key_" + sortedUsers + ".key");

        if (keyFile.exists()) {
            // Загрузка существующего ключа из файла
            try (FileInputStream fis = new FileInputStream(keyFile)) {
                byte[] keyBytes = fis.readAllBytes();
                return new SecretKeySpec(keyBytes, "AES");
            }
        } else {
            // Генерация нового ключа
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(128); // 128-битный ключ, потому что aes
            SecretKey key = keyGen.generateKey();

            // Сохранение ключа в фйл
            try (FileOutputStream out = new FileOutputStream(keyFile)) {
                out.write(key.getEncoded());
            }

            return key;
        }
    }

    private boolean hasKeyFor(String otherUser) {
        String sortedUsers = username.compareTo(otherUser) < 0 ? username + "_" + otherUser :
                otherUser + "_" + username;
        File keyFile = new File("key_" + sortedUsers + ".key");
        return keyFile.exists();
    }

    private boolean isBase64(String message) {
        try {
            Base64.getDecoder().decode(message);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

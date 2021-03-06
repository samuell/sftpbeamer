package no.neic.tryggve;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import no.neic.tryggve.constants.HostName;
import no.neic.tryggve.constants.JsonPropertyName;
import no.neic.tryggve.constants.UrlParam;
import org.apache.commons.lang3.StringUtils;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.*;
import java.nio.file.FileSystems;
import java.util.List;
import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipOutputStream;

public final class HttpRequestFacade {
    private static final Logger logger = LoggerFactory.getLogger(HttpRequestFacade.class);

    private static final String SEPARATOR = FileSystems.getDefault().getSeparator();

    private static final String HOST1 = "host1";
    private static final String HOST2 = "host2";

    public static void fetchInfoHandler(RoutingContext routingContext) {
        String appInfo = "./app.info.json";
        File file = new File(appInfo);
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            if (file.exists()) {
                br = new BufferedReader(new FileReader(appInfo));
            } else {
                br = new BufferedReader(new InputStreamReader(HttpRequestFacade.class.getClassLoader().getResourceAsStream("app.info.json")));
            }
            String line = br.readLine();
            while (line != null) {
                sb.append(line);
                line = br.readLine();
            }
            routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).end(sb.toString());
        } catch(IOException e) {
            logger.error(e);
            routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {}
            }
        }
    }

    /**
     * This method receives a request of connecting remote host. The request body looks like
     * {"username": "", "password": "", "hostname": "", "port": 12, "otc": "", "source": ""}
     */
    public static void loginHandler(RoutingContext routingContext) {
        JsonObject requestJsonBody = routingContext.getBodyAsJson();
        String userName = requestJsonBody.getString(JsonPropertyName.USERNAME);
        String otc = requestJsonBody.getString(JsonPropertyName.OTC);
        String password = requestJsonBody.getString(JsonPropertyName.PASSWORD);
        String hostName = requestJsonBody.getString(JsonPropertyName.HOSTNAME);
        String port = requestJsonBody.getString(JsonPropertyName.PORT);
        String source = requestJsonBody.getString(JsonPropertyName.SOURCE);

        if (StringUtils.isEmpty(userName)
                || StringUtils.isEmpty(password)
                || StringUtils.isEmpty(hostName)
                || StringUtils.isEmpty(port)
                || StringUtils.isEmpty(source)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else if (!source.equals(HOST1) && !source.equals(HOST2)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else if (!StringUtils.isNumeric(port)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else {
            logger.debug("User {} connects to host {} in port {} through source {}", userName, hostName, port, source);

            ChannelSftp channelSftp = null;

            Session session = routingContext.session();
            String sessionId = session.id();

            SftpConnectionManager sftpSessionManager = SftpConnectionManager.getManager();
            try {

                logger.debug("User {} is Connecting to host {}", userName, hostName);
                if (otc.isEmpty()) {
                    sftpSessionManager.createSftpConnection(sessionId, source, userName, password, hostName, Integer.parseInt(port));
                } else {
                    sftpSessionManager.createSftpConnection(sessionId, source, userName, password, otc, hostName, Integer.parseInt(port));
                }
                logger.debug("User {} is Connected to host, try to open a channel", userName);
                channelSftp = sftpSessionManager.getSftpConnection(sessionId, source);

                logger.debug("The channel is opened");
                String homePath;
                if (hostName.equals(HostName.TSD)) {
                    homePath = SEPARATOR + userName.split("-")[0];
                } else if (hostName.equals(HostName.MOSLER)) {
                    homePath = SEPARATOR;
                } else {
                    homePath = channelSftp.getHome();
                }

                logger.debug("Fetching the content at home {}", homePath);
                Vector<ChannelSftp.LsEntry> entryVector = channelSftp.ls(homePath);
                List<List<String>> entryList = Utils.assembleFolderContent(entryVector, channelSftp, homePath);
                JsonObject responseJson = new JsonObject();
                responseJson.put(JsonPropertyName.DATA, new JsonArray(entryList));
                responseJson.put(JsonPropertyName.HOME, homePath);
                logger.debug("User {} connects to host {} successfully", userName, hostName);
                routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).end(responseJson.encode());
            } catch (JSchException e) {
                logger.error("User {} failed to connect to host {}, because error {} happens.", userName, hostName, e.getMessage());
                routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end(e.getMessage());
            } catch (SftpException e) {
                logger.error("User {} succeeded in connecting to host {}, but failed to fetch the content of home.", userName, hostName);
                sftpSessionManager.disconnectSftp(sessionId, source);
                routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end(e.getMessage());
            } finally {
                if (channelSftp != null && channelSftp.isConnected()) {
                    channelSftp.disconnect();
                }
            }
        }
    }

    /**
     * This method is used to check if a file is existing or not.
     *
     */
    public static void checkFileHandler(RoutingContext routingContext) {
        JsonObject requestJsonBody = routingContext.getBodyAsJson();
        String source = requestJsonBody.getString(JsonPropertyName.SOURCE);
        String path = requestJsonBody.getString(JsonPropertyName.PATH);

        if (StringUtils.isEmpty(source) || StringUtils.isEmpty(path)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else if (!source.equals(HOST1) && !source.equals(HOST2)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else {
            logger.debug("Check if file {} is existing or not.", path);

            Session session = routingContext.session();
            String sessionId = session.id();

            ChannelSftp channelSftp = null;
            try {
                channelSftp = SftpConnectionManager.getManager().getSftpConnection(sessionId, source);

                try {
                    channelSftp.lstat(path);
                    logger.debug("File {} is existing.", path);
                    routingContext.response().setStatusCode(HttpResponseStatus.FOUND.code()).end();
                } catch (SftpException e) {
                    logger.debug("File {} is not existing.", path);
                    routingContext.response().setStatusCode(HttpResponseStatus.NOT_FOUND.code()).end();
                }
            } catch (JSchException e) {
                logger.error(e);
                routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end(e.getMessage());
            } finally {
                if (channelSftp != null && channelSftp.isConnected()) {
                    channelSftp.disconnect();
                }
            }
        }
    }

    /**
     * This method is used to receive a request of creating a new folder. The request body looks like {"source": "", "path": ""}.
     * The path parameter represents absolute path of a newly created folder.
     *
     */
    public static void createFolderHandler(RoutingContext routingContext) {
        JsonObject requestJsonBody = routingContext.getBodyAsJson();
        String source = requestJsonBody.getString(JsonPropertyName.SOURCE);
        String path = requestJsonBody.getString(JsonPropertyName.PATH);

        if (StringUtils.isEmpty(source) || StringUtils.isEmpty(path)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else if (!source.equals(HOST1) && !source.equals(HOST2)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else {
            logger.debug("Source {} wants to create a new folder {}.", source, path);
            Session session = routingContext.session();
            String sessionId = session.id();

            ChannelSftp channelSftp = null;
            try {
                channelSftp = SftpConnectionManager.getManager().getSftpConnection(sessionId, source);

                try {
                    channelSftp.mkdir(path);
                } catch (SftpException e) {
                    logger.debug(e);
                }
                logger.debug("Folder {} is created.", path);
                routingContext.response().setStatusCode(HttpResponseStatus.CREATED.code()).end();
            } catch (JSchException e) {
                logger.debug("Failed to create a folder {}", path);
                logger.error(e);
                routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end(e.getMessage());
            } finally {
                if (channelSftp != null && channelSftp.isConnected()) {
                    channelSftp.disconnect();
                }
            }
        }
    }

    /**
     * This method is used to receive a request of renaming a file or a folder. The request body looks like
     * {"source": "", "path": "", "old_name": "", "new_name": ""}. The path parameter represents the absolute path
     * where the file or folder you want to rename is.
     *
     */
    public static void renameHandler(RoutingContext routingContext) {
        JsonObject requestJsonBody = routingContext.getBodyAsJson();
        String source = requestJsonBody.getString(JsonPropertyName.SOURCE);
        String path = requestJsonBody.getString(JsonPropertyName.PATH);
        String old_name = requestJsonBody.getString(JsonPropertyName.OLD_NAME);
        String new_name = requestJsonBody.getString(JsonPropertyName.NEW_NAME);

        if (StringUtils.isEmpty(source) || StringUtils.isEmpty(path) || StringUtils.isEmpty(old_name) || StringUtils.isEmpty(new_name)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else if (!source.equals(HOST1) && !source.equals(HOST2)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else {
            logger.debug("Source {} renames the file or folder {} under {} to {}", source, old_name, path, new_name);

            Session session = routingContext.session();
            String sessionId = session.id();

            ChannelSftp channelSftp = null;
            try {
                channelSftp = SftpConnectionManager.getManager().getSftpConnection(sessionId, source);
                String newPath = StringUtils.join(path, SEPARATOR, new_name);
                SftpATTRS attrs = null;
                try {
                    attrs = channelSftp.lstat(newPath);
                } catch (SftpException e) {}
                if (attrs != null) {
                    routingContext.response().setStatusCode(HttpResponseStatus.FOUND.code()).end();
                } else {
                    channelSftp.rename(StringUtils.join(path, SEPARATOR, old_name), newPath);
                    routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).end();
                }
            } catch (SftpException | JSchException e) {
                logger.debug("Failed to rename the file or folder {} in {}", old_name, path);
                logger.error(e);
                routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end(e.getMessage());
            } finally {
                if (channelSftp != null && channelSftp.isConnected()) {
                    channelSftp.disconnect();
                }
            }
        }
    }

    /**
     * This method is used to do some preparation before starting to transfer data between two remote hosts.
     * The request body looks like {"from": {"name": "", "path": "", "data": {"file": [], "folder": []}}, "to": {"name": "", "path": ""}}.
     *
     */
    public static void transferPrepareHandler(RoutingContext routingContext) {
        JsonObject requestJsonBody = routingContext.getBodyAsJson();
        JsonObject fromJsonObject = requestJsonBody.getJsonObject(JsonPropertyName.FROM);
        JsonObject toJsonObject = requestJsonBody.getJsonObject(JsonPropertyName.TO);

        if (fromJsonObject == null || toJsonObject == null) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else {
            String fromPath = fromJsonObject.getString(JsonPropertyName.PATH);
            String toPath = toJsonObject.getString(JsonPropertyName.PATH);

            String fromName = fromJsonObject.getString(JsonPropertyName.NAME);
            String toName = toJsonObject.getString(JsonPropertyName.NAME);

            if (StringUtils.isEmpty(fromPath) || StringUtils.isEmpty(toPath) || StringUtils.isEmpty(fromName) || StringUtils.isEmpty(toName)) {
                routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
            } else {
                JsonObject data = fromJsonObject.getJsonObject(JsonPropertyName.DATA);

                if (data == null) {
                    routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
                } else {
                    JsonArray fileArray = data.getJsonArray(JsonPropertyName.FILE);
                    JsonArray folderArray = data.getJsonArray(JsonPropertyName.FOLDER);

                    if ((fileArray == null || fileArray.size() == 0) && (folderArray == null || folderArray.size() == 0)) {
                        routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
                    } else {
                        Session session = routingContext.session();
                        String sessionId = session.id();

                        ChannelSftp channelSftpFrom = null;
                        ChannelSftp channelSftpTo = null;
                        try {
                            channelSftpFrom = SftpConnectionManager.getManager().getSftpConnection(sessionId, fromName);
                            channelSftpTo = SftpConnectionManager.getManager().getSftpConnection(sessionId, toName);


                            String existingFile = null;
                            String existingFolder = null;

                            if (fileArray != null) {
                                for (Object fileName : fileArray) {
                                    try {
                                        channelSftpTo.lstat(StringUtils.join(toPath, SEPARATOR, fileName.toString()));
                                        existingFile = fileName.toString();
                                        break;
                                    } catch (SftpException e) {
                                    }
                                }
                            }

                            if (folderArray != null) {
                                for (Object folderName : folderArray) {
                                    try {
                                        channelSftpTo.lstat(StringUtils.join(toPath, SEPARATOR, folderName.toString()));
                                        existingFolder = folderName.toString();
                                        break;
                                    } catch (SftpException e) {
                                    }
                                }
                            }

                            if (existingFile != null || existingFolder != null) {
                                JsonObject existingItems = new JsonObject();
                                if (existingFile != null) {
                                    existingItems.put(JsonPropertyName.FILE, existingFile);
                                }
                                if (existingFolder != null) {
                                    existingItems.put(JsonPropertyName.FOLDER, existingFolder);
                                }

                                routingContext.response().setStatusCode(HttpResponseStatus.FOUND.code()).end(existingItems.encode());
                            } else {
                                FolderNode root = new FolderNode();
                                root.folderName = fromPath;


                                if (fileArray != null) {
                                    fileArray.stream().forEach(fileName -> root.fileNodeList.add(fileName.toString()));
                                }

                                if (folderArray != null) {
                                    FolderNode folderNode;
                                    for (Object folderName : folderArray) {
                                        String path = StringUtils.join(fromPath, SEPARATOR, folderName);
                                        folderNode = Utils.assembleFolderInfo(channelSftpFrom, path, folderName.toString());
                                        if (folderNode != null) {
                                            root.folderNodeList.add(folderNode);
                                        }
                                    }
                                }

                                root.createFolder(true, channelSftpTo, toPath);

                                routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).end(new JsonArray(root.getRelativeFilePathArray("")).encode());
                            }

                        } catch (JSchException e) {
                            logger.error(e);
                            routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
                        } finally {
                            if (channelSftpFrom != null && channelSftpFrom.isConnected()) {
                                channelSftpFrom.disconnect();
                            }
                            if (channelSftpTo != null && channelSftpTo.isConnected()) {
                                channelSftpTo.disconnect();
                            }
                        }
                    }
                }
            }

        }
    }

    /**
     * This method is used to list content of a folder.
     *
     */
    public static void listHandler(RoutingContext routingContext) {

        String path = routingContext.request().getParam(UrlParam.PATH);
        String source = routingContext.request().getParam(UrlParam.SOURCE);

        if (StringUtils.isEmpty(path) || StringUtils.isEmpty(source)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else if (!source.equals(HOST1) && !source.equals(HOST2)) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else {
            logger.debug("List the content of folder {}", path);
            Session session = routingContext.session();
            String sessionId = session.id();

            ChannelSftp channelSftp = null;
            try {
                channelSftp = SftpConnectionManager.getManager().getSftpConnection(sessionId, source);

                Vector<ChannelSftp.LsEntry> entryVector = channelSftp.ls(path);
                List<List<String>> entryList = Utils.assembleFolderContent(entryVector, channelSftp, path);
                JsonObject responseJson = new JsonObject();
                responseJson.put(JsonPropertyName.PATH, path).put(JsonPropertyName.DATA, new JsonArray(entryList));


                routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).end(responseJson.encode());
            } catch (SftpException | JSchException e) {
                logger.debug("Failed to list the content of folder {}", path);
                logger.error(e);
                routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
            } finally {
                if (channelSftp != null && channelSftp.isConnected()) {
                    channelSftp.disconnect();
                }
            }
        }
    }

    /**
     * This method is used to delete files or folders. The request body looks like {"source": "", "path": "", "data": []}.
     *
     */
    public static void deleteHandler(RoutingContext routingContext) {
        JsonObject requestJsonBody = routingContext.getBodyAsJson();
        String path = requestJsonBody.getString(JsonPropertyName.PATH);
        String source = requestJsonBody.getString(JsonPropertyName.SOURCE);
        JsonArray data = requestJsonBody.getJsonArray(JsonPropertyName.DATA);

        if (StringUtils.isEmpty(path) || StringUtils.isEmpty(source) || data == null || data.size() == 0) {
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
        } else {
            logger.debug("Delete the data of {}", path);

            Session session = routingContext.session();
            String sessionId = session.id();

            ChannelSftp channelSftp = null;
            try {
                channelSftp = SftpConnectionManager.getManager().getSftpConnection(sessionId, source);

                JsonObject item;
                for (Object object : data) {
                    item = (JsonObject) object;
                    String str = StringUtils.join(path, SEPARATOR, item.getString(JsonPropertyName.NAME));
                    if (item.getString(JsonPropertyName.TYPE).equals(JsonPropertyName.FILE)) {
                        logger.debug("Deleting a file {}", str);
                        channelSftp.rm(str);
                    } else {
                        logger.debug("Deleting a folder {}", str);
                        Utils.deleteFolder(str, channelSftp);
                    }
                }
                routingContext.response().setStatusCode(HttpResponseStatus.NO_CONTENT.code()).end();

            } catch (SftpException | JSchException e) {
                logger.debug("Failed to delete the data of {}", path);
                logger.error(e);
                routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
            } finally {
                if (channelSftp != null && channelSftp.isConnected()) {
                    channelSftp.disconnect();
                }
            }
        }
    }

    /**
     * This method is used to disconnect from the remote host and clean up the kept connection.
     *
     */
    public static void disconnectHandler(RoutingContext routingContext) {
        String source = routingContext.request().getParam(UrlParam.SOURCE);
        Session session = routingContext.session();
        String sessionId = session.id();
        if (source == null || source.isEmpty()) {
            SftpConnectionManager.getManager().disconnectSftp(sessionId);
        } else {
            SftpConnectionManager.getManager().disconnectSftp(sessionId, source);
        }

        routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).end();
    }

    public static void downloadCheckHandler(RoutingContext routingContext) {
        String source = routingContext.request().getParam(UrlParam.SOURCE);
        String sessionId = routingContext.session().id();
        if (DownloadCounter.getCounter().checkIfBusy(sessionId + source)) {
            routingContext.response().setStatusCode(HttpResponseStatus.NOT_ACCEPTABLE.code()).end();
        } else {
            routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).end();
        }
    }

    public static void downloadHandler(RoutingContext routingContext) {
        String source = routingContext.request().getParam(UrlParam.SOURCE);
        String path = routingContext.request().getParam(UrlParam.PATH);
        String sessionId = routingContext.session().id();

        DownloadCounter.getCounter().addDownloadTask(sessionId + source);
        int bufferSize = 2 * 4096;
        logger.debug("Download file {}", path);
        logger.debug("Source is {}", source);

        ChannelSftp channelSftp = null;
        try {
            channelSftp = SftpConnectionManager.getManager().getDownloadConnection(sessionId, source);
            ChannelSftp temp = channelSftp;


            HttpServerResponse response = routingContext.response();

            AtomicLong readSize = new AtomicLong(0);

            SftpATTRS attrs = channelSftp.lstat(path);
            long fileSize = attrs.getSize();

            response.putHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                    + path.substring(path.lastIndexOf(FileSystems.getDefault().getSeparator()) + 1) + "\"");
            response.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileSize));
            response.putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM);
            response.setWriteQueueMaxSize(bufferSize);

            InputStream inputStream = new BufferedInputStream(channelSftp.get(path), 2 * bufferSize);

            response.exceptionHandler(throwable -> {
                logger.error(throwable);
                if (temp.isConnected()) {
                    temp.disconnect();
                }
                DownloadCounter.getCounter().removeDownloadTask(sessionId + source);
            }).closeHandler(Void -> {
                if (readSize.get() < fileSize) {
                    if (temp.isConnected()) {
                        temp.disconnect();
                    }
                    DownloadCounter.getCounter().removeDownloadTask(sessionId + source);
                }
            });

            byte[] bytes = new byte[bufferSize];
            AtomicBoolean isWritable = new AtomicBoolean(true);
            response.drainHandler(Void -> isWritable.set(true));

            int size;
            while ((size = inputStream.read(bytes)) != -1) {
                readSize.getAndAdd(size);

                while (!isWritable.get()) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                    }
                }
                if (size >= bufferSize) {
                    response.write(Buffer.buffer().appendBytes(bytes));
                } else {
                    response.write(Buffer.buffer().appendBytes(bytes, 0, size));
                }

                if (response.writeQueueFull()) {
                    isWritable.set(false);
                }
            }

            if (channelSftp.isConnected()) {
                channelSftp.disconnect();
            }
            DownloadCounter.getCounter().removeDownloadTask(sessionId + source);
            response.end();
        } catch (JSchException | SftpException | IOException e) {

            logger.error(e);
            if (!(e instanceof JSchException)) {
                if (channelSftp.isConnected()) {
                    channelSftp.disconnect();
                }
            }
            DownloadCounter.getCounter().removeDownloadTask(sessionId + source);
            routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
        }
    }

    public static void downloadZipHandler(RoutingContext routingContext) {
        String source = routingContext.request().getParam(UrlParam.SOURCE);
        String path = routingContext.request().getParam(UrlParam.PATH);
        String sessionId = routingContext.session().id();
        String rootPath = path.substring(0, path.lastIndexOf(SEPARATOR));
        String relativePath = path.substring(path.lastIndexOf(SEPARATOR) + 1);

        logger.debug("Download folder {} as zip", path);
        logger.debug("Source is {}", source);

        HttpServerResponse response = routingContext.response();
        response.setWriteQueueMaxSize(2 * 4096);
        response.setChunked(true);
        response.putHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                + relativePath + ".zip" + "\"");
        response.putHeader(HttpHeaders.CONTENT_TYPE, "application/zip");

        AtomicBoolean isWritable = new AtomicBoolean(true);
        ChannelSftp channelSftp = null;
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new DownloadZipOutputStream(response, isWritable))) {
            channelSftp = SftpConnectionManager.getManager().getSftpConnection(sessionId, source);

            ChannelSftp temp = channelSftp;
            response.closeHandler(Void -> {
                if (temp.isConnected()) {
                    temp.disconnect();
                }
            }).exceptionHandler(throwable -> {
                logger.error(throwable);
                if (temp.isConnected()) {
                    temp.disconnect();
                }
            }).drainHandler(Void -> isWritable.set(true));

            FolderNode folderNode = new FolderNode(zipOutputStream, channelSftp, rootPath, relativePath, isWritable);
            folderNode.startToZip();
            zipOutputStream.flush();
        } catch (IOException | JSchException | SftpException e) {
            logger.error(e);
        } finally {
            if (channelSftp != null && channelSftp.isConnected()) {
                channelSftp.disconnect();
            }
        }
    }

    public static void chunkUploadHandler(RoutingContext routingContext) {
        String fileName = routingContext.request().getHeader(HttpHeaders.CONTENT_DISPOSITION).split("filename=")[1]; //get the name of a uploaded file
        fileName = fileName.substring(1, fileName.length() - 1).replace("%20", " "); // convert %20 to empty space
        String source = routingContext.request().getParam(UrlParam.SOURCE);
        /**
         * This parameter represents where the data should be uploaded
         */
        String path = routingContext.request().getParam(UrlParam.PATH);
        String sessionId = routingContext.session().id();

        logger.debug("Upload file {} to path {}", fileName, path);
        try {
            ChannelSftp channelSftp = SftpConnectionManager.getManager().getSftpConnection(sessionId, source);


            try {
                OutputStream ops = channelSftp.put(StringUtils.join(path, FileSystems.getDefault().getSeparator(), fileName), ChannelSftp.APPEND);

                Buffer body = routingContext.getBody();
                ops.write(body.getBytes());
                ops.close();
                if (channelSftp.isConnected()) {
                    channelSftp.disconnect();
                }
                routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).end();

            } catch (SftpException | IOException e) {
                logger.error(e);
                if (channelSftp.isConnected()) {
                    channelSftp.disconnect();
                }
                routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
            }

        } catch (JSchException e) {
            logger.error(e);
            routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
        }
    }

}

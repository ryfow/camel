/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.file.remote;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import org.apache.camel.Exchange;
import org.apache.camel.InvalidPayloadException;
import org.apache.camel.component.file.FileComponent;
import org.apache.camel.component.file.GenericFile;
import org.apache.camel.component.file.GenericFileEndpoint;
import org.apache.camel.component.file.GenericFileExist;
import org.apache.camel.component.file.GenericFileOperationFailedException;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.FileUtil;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import static org.apache.camel.util.ObjectHelper.isNotEmpty;

/**
 * SFTP remote file operations
 */
public class SftpOperations implements RemoteFileOperations<ChannelSftp.LsEntry> {
    private static final transient Log LOG = LogFactory.getLog(SftpOperations.class);
    private RemoteFileEndpoint endpoint;
    private ChannelSftp channel;
    private Session session;

    public void setEndpoint(GenericFileEndpoint endpoint) {
        this.endpoint = (RemoteFileEndpoint) endpoint;
    }

    public boolean connect(RemoteFileConfiguration configuration) throws GenericFileOperationFailedException {
        if (isConnected()) {
            // already connected
            return true;
        }

        boolean connected = false;
        int attempt = 0;

        while (!connected) {
            try {
                if (LOG.isTraceEnabled() && attempt > 0) {
                    LOG.trace("Reconnect attempt #" + attempt + " connecting to + " + configuration.remoteServerInformation());
                }

                if (channel == null || !channel.isConnected()) {
                    if (session == null || !session.isConnected()) {
                        LOG.trace("Session isn't connected, trying to recreate and connect.");
                        session = createSession(configuration);
                        session.connect();
                    }
                    LOG.trace("Channel isn't connected, trying to recreate and connect.");
                    channel = (ChannelSftp) session.openChannel("sftp");
                    channel.connect();
                    LOG.info("Connected to " + configuration.remoteServerInformation());
                }

                // yes we could connect
                connected = true;
            } catch (Exception e) {
                GenericFileOperationFailedException failed = new GenericFileOperationFailedException("Cannot connect to " + configuration.remoteServerInformation(), e);
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Cannot connect due: " + failed.getMessage());
                }
                attempt++;
                if (attempt > endpoint.getMaximumReconnectAttempts()) {
                    throw failed;
                }
                if (endpoint.getReconnectDelay() > 0) {
                    try {
                        Thread.sleep(endpoint.getReconnectDelay());
                    } catch (InterruptedException e1) {
                        // ignore
                    }
                }
            }
        }

        return true;
    }

    protected Session createSession(final RemoteFileConfiguration configuration) throws JSchException {
        final JSch jsch = new JSch();

        SftpConfiguration sftpConfig = (SftpConfiguration) configuration;

        if (isNotEmpty(sftpConfig.getPrivateKeyFile())) {
            LOG.debug("Using private keyfile: " + sftpConfig.getPrivateKeyFile());
            if (isNotEmpty(sftpConfig.getPrivateKeyFilePassphrase())) {
                jsch.addIdentity(sftpConfig.getPrivateKeyFile(), sftpConfig.getPrivateKeyFilePassphrase());
            } else {
                jsch.addIdentity(sftpConfig.getPrivateKeyFile());
            }
        }

        if (isNotEmpty(sftpConfig.getKnownHostsFile())) {
            LOG.debug("Using knownhosts file: " + sftpConfig.getKnownHostsFile());
            jsch.setKnownHosts(sftpConfig.getKnownHostsFile());
        }

        final Session session = jsch.getSession(configuration.getUsername(), configuration.getHost(), configuration.getPort());

        if (isNotEmpty(sftpConfig.getStrictHostKeyChecking())) {
            LOG.debug("Using StrickHostKeyChecking: " + sftpConfig.getStrictHostKeyChecking());
            session.setConfig("StrictHostKeyChecking", sftpConfig.getStrictHostKeyChecking());
        }

        // set user information
        session.setUserInfo(new UserInfo() {
            public String getPassphrase() {
                return null;
            }

            public String getPassword() {
                return configuration.getPassword();
            }

            public boolean promptPassword(String s) {
                return true;
            }

            public boolean promptPassphrase(String s) {
                return true;
            }

            public boolean promptYesNo(String s) {
                LOG.warn("Server asks for confirmation (yes|no): " + s + ". Camel will answer no.");
                // Return 'false' indicating modification of the hosts file is disabled.
                return false;
            }

            public void showMessage(String s) {
                LOG.trace("Message received from Server: " + s);
            }
        });
        return session;
    }

    public boolean isConnected() throws GenericFileOperationFailedException {
        return session != null && session.isConnected() && channel != null && channel.isConnected();
    }

    public void disconnect() throws GenericFileOperationFailedException {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
    }

    public boolean deleteFile(String name) throws GenericFileOperationFailedException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Deleting file: " + name);
        }
        try {
            channel.rm(name);
            return true;
        } catch (SftpException e) {
            throw new GenericFileOperationFailedException("Cannot delete file: " + name, e);
        }
    }

    public boolean renameFile(String from, String to) throws GenericFileOperationFailedException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Renaming file: " + from + " to: " + to);
        }
        try {
            channel.rename(from, to);
            return true;
        } catch (SftpException e) {
            throw new GenericFileOperationFailedException("Cannot rename file from: " + from + " to: " + to, e);
        }
    }

    public boolean buildDirectory(String directory, boolean absolute) throws GenericFileOperationFailedException {
        // ignore absolute as all dirs are relative with FTP
        boolean success = false;

        String originalDirectory = getCurrentDirectory();
        try {
            // maybe the full directory already exists
            try {
                channel.cd(directory);
                success = true;
            } catch (SftpException e) {
                // ignore, we could not change directory so try to create it instead
            }

            if (!success) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Trying to build remote directory: " + directory);
                }

                try {
                    channel.mkdir(directory);
                    success = true;
                } catch (SftpException e) {
                    // we are here if the server side doesn't create intermediate folders
                    // so create the folder one by one
                    success = buildDirectoryChunks(directory);
                }
            }
        } catch (IOException e) {
            throw new GenericFileOperationFailedException("Cannot build directory: " + directory, e);
        } catch (SftpException e) {
            throw new GenericFileOperationFailedException("Cannot build directory: " + directory, e);
        } finally {
            // change back to original directory
            if (originalDirectory != null) {
                changeCurrentDirectory(originalDirectory);
            }
        }

        return success;
    }

    private boolean buildDirectoryChunks(String dirName) throws IOException, SftpException {
        final StringBuilder sb = new StringBuilder(dirName.length());
        final String[] dirs = dirName.split("/|\\\\");

        boolean success = false;
        for (String dir : dirs) {
            sb.append(dir).append('/');
            String directory = sb.toString();
            if (LOG.isTraceEnabled()) {
                LOG.trace("Trying to build remote directory by chunk: " + directory);
            }

            // do not try to build root / folder
            if (!directory.equals("/")) {
                try {
                    channel.mkdir(directory);
                    success = true;
                } catch (SftpException e) {
                    // ignore keep trying to create the rest of the path
                }
            }
        }

        return success;
    }

    public String getCurrentDirectory() throws GenericFileOperationFailedException {
        try {
            return channel.pwd();
        } catch (SftpException e) {
            throw new GenericFileOperationFailedException("Cannot get current directory", e);
        }
    }

    public void changeCurrentDirectory(String path) throws GenericFileOperationFailedException {
        try {
            channel.cd(path);
        } catch (SftpException e) {
            throw new GenericFileOperationFailedException("Cannot change current directory to: " + path, e);
        }
    }

    public List<ChannelSftp.LsEntry> listFiles() throws GenericFileOperationFailedException {
        return listFiles(".");
    }

    public List<ChannelSftp.LsEntry> listFiles(String path) throws GenericFileOperationFailedException {
        if (ObjectHelper.isEmpty(path)) {
            // list current directory if file path is not given
            path = ".";
        }

        try {
            final List<ChannelSftp.LsEntry> list = new ArrayList<ChannelSftp.LsEntry>();
            Vector files = channel.ls(path);
            // can return either null or an empty list depending on FTP servers
            if (files != null) {
                for (Object file : files) {
                    list.add((ChannelSftp.LsEntry)file);
                }
            }
            return list;
        } catch (SftpException e) {
            throw new GenericFileOperationFailedException("Cannot list directory: " + path, e);
        }
    }

    public boolean retrieveFile(String name, Exchange exchange) throws GenericFileOperationFailedException {
        if (ObjectHelper.isNotEmpty(endpoint.getLocalWorkDirectory())) {
            // local work directory is configured so we should store file content as files in this local directory
            return retrieveFileToFileInLocalWorkDirectory(name, exchange);
        } else {
            // store file content directory as stream on the body
            return retrieveFileToStreamInBody(name, exchange);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean retrieveFileToStreamInBody(String name, Exchange exchange) throws GenericFileOperationFailedException {
        OutputStream os = null;
        try {
            os = new ByteArrayOutputStream();
            GenericFile<ChannelSftp.LsEntry> target =
                (GenericFile<ChannelSftp.LsEntry>) exchange.getProperty(FileComponent.FILE_EXCHANGE_FILE);
            ObjectHelper.notNull(target, "Exchange should have the " + FileComponent.FILE_EXCHANGE_FILE + " set");
            target.setBody(os);
            channel.get(name, os);
            return true;
        } catch (SftpException e) {
            throw new GenericFileOperationFailedException("Cannot retrieve file: " + name, e);
        } finally {
            IOHelper.close(os, "retrieve: " + name, LOG);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean retrieveFileToFileInLocalWorkDirectory(String name, Exchange exchange) throws GenericFileOperationFailedException {
        File temp;
        File local = new File(endpoint.getLocalWorkDirectory());
        OutputStream os;
        GenericFile<ChannelSftp.LsEntry> file = 
            (GenericFile<ChannelSftp.LsEntry>) exchange.getProperty(FileComponent.FILE_EXCHANGE_FILE);
        ObjectHelper.notNull(file, "Exchange should have the " + FileComponent.FILE_EXCHANGE_FILE + " set");
        try {
            // use relative filename in local work directory
            String relativeName = file.getRelativeFilePath();

            temp = new File(local, relativeName + ".inprogress");
            local = new File(local, relativeName);

            // create directory to local work file
            local.mkdirs();

            // delete any existing files
            if (temp.exists()) {
                if (!FileUtil.deleteFile(temp)) {
                    throw new GenericFileOperationFailedException("Cannot delete existing local work file: " + temp);
                }
            }
            if (local.exists()) {
                if (!FileUtil.deleteFile(local)) {
                    throw new GenericFileOperationFailedException("Cannot delete existing local work file: " + local);
                }
            }

            // create new temp local work file
            if (!temp.createNewFile()) {
                throw new GenericFileOperationFailedException("Cannot create new local work file: " + temp);
            }

            // store content as a file in the local work directory in the temp handle
            os = new FileOutputStream(temp);

            // set header with the path to the local work file
            exchange.getIn().setHeader(Exchange.FILE_LOCAL_WORK_PATH, local.getPath());
        } catch (Exception e) {
            throw new GenericFileOperationFailedException("Cannot create new local work file: " + local);
        }


        try {
            // store the java.io.File handle as the body
            file.setBody(local);
            channel.get(name, os);
        } catch (SftpException e) {
            throw new GenericFileOperationFailedException("Cannot retrieve file: " + name, e);
        } finally {
            IOHelper.close(os, "retrieve: " + name, LOG);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Retrieve file to local work file result: true");
        }

        // operation went okay so rename temp to local after we have retrieved the data
        if (LOG.isTraceEnabled()) {
            LOG.trace("Renaming local in progress file from: " + temp + " to: " + local);
        }
        if (!FileUtil.renameFile(temp, local)) {
            throw new GenericFileOperationFailedException("Cannot rename local work file from: " + temp + " to: " + local);
        }

        return true;
    }

    public boolean storeFile(String name, Exchange exchange) throws GenericFileOperationFailedException {
        // if an existing file already exists what should we do?
        if (endpoint.getFileExist() == GenericFileExist.Ignore || endpoint.getFileExist() == GenericFileExist.Fail) {
            boolean existFile = existsFile(name);
            if (existFile && endpoint.getFileExist() == GenericFileExist.Ignore) {
                // ignore but indicate that the file was written
                if (LOG.isTraceEnabled()) {
                    LOG.trace("An existing file already exists: " + name + ". Ignore and do not override it.");
                }
                return true;
            } else if (existFile && endpoint.getFileExist() == GenericFileExist.Fail) {
                throw new GenericFileOperationFailedException("File already exist: " + name + ". Cannot write new file.");
            }
        }

        InputStream is = null;
        try {
            is = ExchangeHelper.getMandatoryInBody(exchange, InputStream.class);
            if (endpoint.getFileExist() == GenericFileExist.Append) {
                channel.put(is, name, ChannelSftp.APPEND);
            } else {
                // override is default
                channel.put(is, name);
            }
            return true;
        } catch (SftpException e) {
            throw new GenericFileOperationFailedException("Cannot store file: " + name, e);
        } catch (InvalidPayloadException e) {
            throw new GenericFileOperationFailedException("Cannot store file: " + name, e);
        } finally {
            IOHelper.close(is, "store: " + name, LOG);
        }
    }

    public boolean existsFile(String name) throws GenericFileOperationFailedException {
        // check whether a file already exists
        String directory = FileUtil.onlyPath(name);
        if (directory == null) {
            return false;
        }

        String onlyName = FileUtil.stripPath(name);
        try {
            Vector files = channel.ls(directory);
            // can return either null or an empty list depending on FTP servers
            if (files == null) {
                return false;
            }
            for (Object file : files) {
                ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) file;
                if (entry.getFilename().equals(onlyName)) {
                    return true;
                }
            }
            return false;
        } catch (SftpException e) {
            // or an exception can be thrown with id 2 which means file does not exists
            if (ChannelSftp.SSH_FX_NO_SUCH_FILE == e.id) {
                return false;
            }
            // otherwise its a more serious error so rethrow
            throw new GenericFileOperationFailedException(e.getMessage(), e);
        }
    }

    public boolean sendNoop() throws GenericFileOperationFailedException {
        // is not implemented
        return true;
    }
}
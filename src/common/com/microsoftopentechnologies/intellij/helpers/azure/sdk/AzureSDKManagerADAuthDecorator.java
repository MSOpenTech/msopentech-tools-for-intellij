/**
 * Copyright 2014 Microsoft Open Technologies Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.microsoftopentechnologies.intellij.helpers.azure.sdk;

import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoftopentechnologies.intellij.components.DefaultLoader;
import com.microsoftopentechnologies.intellij.components.PluginSettings;
import com.microsoftopentechnologies.intellij.helpers.CallableSingleArg;
import com.microsoftopentechnologies.intellij.helpers.StringHelper;
import com.microsoftopentechnologies.intellij.helpers.aadauth.AuthenticationContext;
import com.microsoftopentechnologies.intellij.helpers.aadauth.AuthenticationResult;
import com.microsoftopentechnologies.intellij.helpers.azure.AzureCmdException;
import com.microsoftopentechnologies.intellij.helpers.azure.rest.AzureRestAPIHelper;
import com.microsoftopentechnologies.intellij.helpers.azure.rest.AzureRestAPIManager;
import com.microsoftopentechnologies.intellij.helpers.azure.rest.AzureRestAPIManagerImpl;
import com.microsoftopentechnologies.intellij.model.storage.*;
import com.microsoftopentechnologies.intellij.model.storage.TableEntity.Property;
import com.microsoftopentechnologies.intellij.model.vm.*;


import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

public class AzureSDKManagerADAuthDecorator implements AzureSDKManager {
    protected AzureSDKManager sdkManager;

    public AzureSDKManagerADAuthDecorator(AzureSDKManager sdkManager) {
        this.sdkManager = sdkManager;
    }

    private interface Func0<T> {
        T run() throws AzureCmdException;
    }

    protected <T> T runWithRetry(String subscriptionId, Func0<T> func) throws AzureCmdException {
        try {
            return func.run();
        } catch (AzureCmdException e) {
            Throwable throwable = e.getCause();
            if (throwable == null)
                throw e;
            if (!(throwable instanceof ServiceException))
                throw e;

            ServiceException serviceException = (ServiceException) throwable;
            if (serviceException.getHttpStatusCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                // attempt token refresh
                if (refreshAccessToken(subscriptionId)) {
                    // retry request
                    return func.run();
                }
            }

            throw e;
        }
    }

    private boolean refreshAccessToken(String subscriptionId) {
        PluginSettings settings = DefaultLoader.getPluginComponent().getSettings();
        AzureRestAPIManager apiManager = AzureRestAPIManagerImpl.getManager();
        AuthenticationResult token = apiManager.getAuthenticationTokenForSubscription(subscriptionId);

        // check if we have a refresh token to redeem
        if (token != null && !StringHelper.isNullOrWhiteSpace(token.getRefreshToken())) {
            AuthenticationContext context = null;
            try {
                context = new AuthenticationContext(settings.getAdAuthority());
                token = context.acquireTokenByRefreshToken(
                        token,
                        AzureRestAPIHelper.getTenantName(subscriptionId),
                        settings.getAzureServiceManagementUri(),
                        settings.getClientId());
            } catch (Exception e) {
                // if the error is HTTP status code 400 then we need to
                // do interactive auth
                if (e.getMessage().contains("HTTP status code 400")) {
                    try {
                        token = AzureRestAPIHelper.acquireTokenInteractive(subscriptionId, apiManager);
                    } catch (Exception ignored) {
                        token = null;
                    }
                } else {
                    token = null;
                }
            } finally {
                if (context != null) {
                    context.dispose();
                }
            }

            if (token != null) {
                apiManager.setAuthenticationTokenForSubscription(subscriptionId, token);
                return true;
            }
        }

        return false;
    }


    @Override
    public List<CloudService> getCloudServices(final String subscriptionId) throws AzureCmdException {
        return runWithRetry(subscriptionId, new Func0<List<CloudService>>() {
            @Override
            public List<CloudService> run() throws AzureCmdException {
                return sdkManager.getCloudServices(subscriptionId);
            }
        });
    }


    @Override
    public List<VirtualMachine> getVirtualMachines(final String subscriptionId) throws AzureCmdException {
        return runWithRetry(subscriptionId, new Func0<List<VirtualMachine>>() {
            @Override
            public List<VirtualMachine> run() throws AzureCmdException {
                return sdkManager.getVirtualMachines(subscriptionId);
            }
        });
    }


    @Override
    public VirtualMachine refreshVirtualMachineInformation(final VirtualMachine vm) throws AzureCmdException {
        return runWithRetry(vm.getSubscriptionId(), new Func0<VirtualMachine>() {
            @Override
            public VirtualMachine run() throws AzureCmdException {
                return sdkManager.refreshVirtualMachineInformation(vm);
            }
        });
    }

    @Override
    public void startVirtualMachine(final VirtualMachine vm) throws AzureCmdException {
        runWithRetry(vm.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.startVirtualMachine(vm);
                return null;
            }
        });
    }

    @Override
    public void shutdownVirtualMachine(final VirtualMachine vm, final boolean deallocate) throws AzureCmdException {
        runWithRetry(vm.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.shutdownVirtualMachine(vm, deallocate);
                return null;
            }
        });
    }

    @Override
    public void restartVirtualMachine(final VirtualMachine vm) throws AzureCmdException {
        runWithRetry(vm.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.restartVirtualMachine(vm);
                return null;
            }
        });
    }

    @Override
    public void deleteVirtualMachine(final VirtualMachine vm, final boolean deleteFromStorage) throws AzureCmdException {
        runWithRetry(vm.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.deleteVirtualMachine(vm, deleteFromStorage);
                return null;
            }
        });
    }


    @Override
    public byte[] downloadRDP(final VirtualMachine vm) throws AzureCmdException {
        return runWithRetry(vm.getSubscriptionId(), new Func0<byte[]>() {
            @Override
            public byte[] run() throws AzureCmdException {
                return sdkManager.downloadRDP(vm);
            }
        });
    }


    @Override
    public List<StorageAccount> getStorageAccounts(final String subscriptionId) throws AzureCmdException {
        return runWithRetry(subscriptionId, new Func0<List<StorageAccount>>() {
            @Override
            public List<StorageAccount> run() throws AzureCmdException {
                return sdkManager.getStorageAccounts(subscriptionId);
            }
        });
    }


    @Override
    public List<VirtualMachineImage> getVirtualMachineImages(final String subscriptionId) throws AzureCmdException {
        return runWithRetry(subscriptionId, new Func0<List<VirtualMachineImage>>() {
            @Override
            public List<VirtualMachineImage> run() throws AzureCmdException {
                return sdkManager.getVirtualMachineImages(subscriptionId);
            }
        });
    }


    @Override
    public List<VirtualMachineSize> getVirtualMachineSizes(final String subscriptionId) throws AzureCmdException {
        return runWithRetry(subscriptionId, new Func0<List<VirtualMachineSize>>() {
            @Override
            public List<VirtualMachineSize> run() throws AzureCmdException {
                return sdkManager.getVirtualMachineSizes(subscriptionId);
            }
        });
    }


    @Override
    public List<Location> getLocations(final String subscriptionId) throws AzureCmdException {
        return runWithRetry(subscriptionId, new Func0<List<Location>>() {
            @Override
            public List<Location> run() throws AzureCmdException {
                return sdkManager.getLocations(subscriptionId);
            }
        });
    }


    @Override
    public List<AffinityGroup> getAffinityGroups(final String subscriptionId) throws AzureCmdException {
        return runWithRetry(subscriptionId, new Func0<List<AffinityGroup>>() {
            @Override
            public List<AffinityGroup> run() throws AzureCmdException {
                return sdkManager.getAffinityGroups(subscriptionId);
            }
        });
    }


    @Override
    public List<VirtualNetwork> getVirtualNetworks(final String subscriptionId) throws AzureCmdException {
        return runWithRetry(subscriptionId, new Func0<List<VirtualNetwork>>() {
            @Override
            public List<VirtualNetwork> run() throws AzureCmdException {
                return sdkManager.getVirtualNetworks(subscriptionId);
            }
        });
    }

    @Override
    public void createStorageAccount(final StorageAccount storageAccount) throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.createStorageAccount(storageAccount);
                return null;
            }
        });
    }

    @Override
    public void createCloudService(final CloudService cloudService) throws AzureCmdException {
        runWithRetry(cloudService.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.createCloudService(cloudService);
                return null;
            }
        });
    }

    @Override
    public void createVirtualMachine(final VirtualMachine virtualMachine,
                                     final VirtualMachineImage vmImage,
                                     final StorageAccount storageAccount,
                                     final String virtualNetwork,
                                     final String username,
                                     final String password,
                                     final byte[] certificate)
            throws AzureCmdException {
        runWithRetry(virtualMachine.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.createVirtualMachine(virtualMachine, vmImage, storageAccount, virtualNetwork,
                        username, password, certificate);
                return null;
            }
        });
    }

    @Override
    public void createVirtualMachine(final VirtualMachine virtualMachine,
                                     final VirtualMachineImage vmImage,
                                     final String mediaLocation,
                                     final String virtualNetwork,
                                     final String username,
                                     final String password,
                                     final byte[] certificate)
            throws AzureCmdException {
        runWithRetry(virtualMachine.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.createVirtualMachine(virtualMachine, vmImage, mediaLocation, virtualNetwork,
                        username, password, certificate);
                return null;
            }
        });
    }


    @Override
    public StorageAccount refreshStorageAccountInformation(final StorageAccount storageAccount)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<StorageAccount>() {
            @Override
            public StorageAccount run() throws AzureCmdException {
                return sdkManager.refreshStorageAccountInformation(storageAccount);
            }
        });
    }

    @Override
    public String createServiceCertificate(final String subscriptionId, final String serviceName,
                                           final byte[] data, final String password)
            throws AzureCmdException {
        return runWithRetry(subscriptionId, new Func0<String>() {
            @Override
            public String run() throws AzureCmdException {
                return sdkManager.createServiceCertificate(subscriptionId, serviceName, data, password);
            }
        });
    }

    @Override
    public void deleteStorageAccount(final StorageAccount storageAccount) throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.deleteStorageAccount(storageAccount);
                return null;
            }
        });
    }


    @Override
    public List<BlobContainer> getBlobContainers(final StorageAccount storageAccount)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<List<BlobContainer>>() {
            @Override
            public List<BlobContainer> run() throws AzureCmdException {
                return sdkManager.getBlobContainers(storageAccount);
            }
        });
    }


    @Override
    public BlobContainer createBlobContainer(final StorageAccount storageAccount,
                                             final BlobContainer blobContainer)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<BlobContainer>() {
            @Override
            public BlobContainer run() throws AzureCmdException {
                return sdkManager.createBlobContainer(storageAccount, blobContainer);
            }
        });
    }

    @Override
    public void deleteBlobContainer(final StorageAccount storageAccount,
                                    final BlobContainer blobContainer)
            throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.deleteBlobContainer(storageAccount, blobContainer);
                return null;
            }
        });
    }


    @Override
    public BlobDirectory getRootDirectory(final StorageAccount storageAccount,
                                          final BlobContainer blobContainer)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<BlobDirectory>() {
            @Override
            public BlobDirectory run() throws AzureCmdException {
                return sdkManager.getRootDirectory(storageAccount, blobContainer);
            }
        });
    }


    @Override
    public List<BlobItem> getBlobItems(final StorageAccount storageAccount,
                                       final BlobDirectory blobDirectory)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<List<BlobItem>>() {
            @Override
            public List<BlobItem> run() throws AzureCmdException {
                return sdkManager.getBlobItems(storageAccount, blobDirectory);
            }
        });
    }


    @Override
    public BlobDirectory createBlobDirectory(final StorageAccount storageAccount,
                                             final BlobDirectory parentBlobDirectory,
                                             final BlobDirectory blobDirectory)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<BlobDirectory>() {
            @Override
            public BlobDirectory run() throws AzureCmdException {
                return sdkManager.createBlobDirectory(storageAccount, parentBlobDirectory, blobDirectory);
            }
        });
    }


    @Override
    public BlobFile createBlobFile(final StorageAccount storageAccount,
                                   final BlobDirectory parentBlobDirectory,
                                   final BlobFile blobFile)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<BlobFile>() {
            @Override
            public BlobFile run() throws AzureCmdException {
                return sdkManager.createBlobFile(storageAccount, parentBlobDirectory, blobFile);
            }
        });
    }

    @Override
    public void deleteBlobFile(final StorageAccount storageAccount,
                               final BlobFile blobFile)
            throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.deleteBlobFile(storageAccount, blobFile);
                return null;
            }
        });
    }


    @Override
    public void uploadBlobFileContent(final StorageAccount storageAccount,
                                      final BlobContainer blobContainer,
                                      final String filePath,
                                      final InputStream content,
                                      final CallableSingleArg<Void, Long> processBlock,
                                      final long maxBlockSize,
                                      final long length) throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.uploadBlobFileContent(storageAccount, blobContainer, filePath, content, processBlock, maxBlockSize, length);
                return null;
            }
        });
    }

    @Override
    public void downloadBlobFileContent(final StorageAccount storageAccount,
                                        final BlobFile blobFile,
                                        final OutputStream content)
            throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.downloadBlobFileContent(storageAccount, blobFile, content);
                return null;
            }
        });
    }


    @Override
    public List<Queue> getQueues(final StorageAccount storageAccount)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<List<Queue>>() {
            @Override
            public List<Queue> run() throws AzureCmdException {
                return sdkManager.getQueues(storageAccount);
            }
        });
    }


    @Override
    public Queue createQueue(final StorageAccount storageAccount, final Queue queue)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<Queue>() {
            @Override
            public Queue run() throws AzureCmdException {
                return sdkManager.createQueue(storageAccount, queue);
            }
        });
    }

    @Override
    public void deleteQueue(final StorageAccount storageAccount, final Queue queue)
            throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.deleteQueue(storageAccount, queue);
                return null;
            }
        });
    }


    @Override
    public List<QueueMessage> getQueueMessages(final StorageAccount storageAccount, final Queue queue)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<List<QueueMessage>>() {
            @Override
            public List<QueueMessage> run() throws AzureCmdException {
                return sdkManager.getQueueMessages(storageAccount, queue);
            }
        });
    }

    @Override
    public void clearQueue(final StorageAccount storageAccount, final Queue queue)
            throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.clearQueue(storageAccount, queue);
                return null;
            }
        });
    }

    @Override
    public void createQueueMessage(final StorageAccount storageAccount,
                                   final QueueMessage queueMessage,
                                   final int timeToLiveInSeconds)
            throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.createQueueMessage(storageAccount, queueMessage, timeToLiveInSeconds);
                return null;
            }
        });
    }


    @Override
    public QueueMessage dequeueFirstQueueMessage(final StorageAccount storageAccount,
                                                 final Queue queue)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<QueueMessage>() {
            @Override
            public QueueMessage run() throws AzureCmdException {
                return sdkManager.dequeueFirstQueueMessage(storageAccount, queue);
            }
        });
    }


    @Override
    public List<Table> getTables(final StorageAccount storageAccount)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<List<Table>>() {
            @Override
            public List<Table> run() throws AzureCmdException {
                return sdkManager.getTables(storageAccount);
            }
        });
    }


    @Override
    public Table createTable(final StorageAccount storageAccount, final Table table)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<Table>() {
            @Override
            public Table run() throws AzureCmdException {
                return sdkManager.createTable(storageAccount, table);
            }
        });
    }

    @Override
    public void deleteTable(final StorageAccount storageAccount, final Table table)
            throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.deleteTable(storageAccount, table);
                return null;
            }
        });
    }


    @Override
    public List<TableEntity> getTableEntities(final StorageAccount storageAccount,
                                              final Table table,
                                              final String filter)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<List<TableEntity>>() {
            @Override
            public List<TableEntity> run() throws AzureCmdException {
                return sdkManager.getTableEntities(storageAccount, table, filter);
            }
        });
    }


    @Override
    public TableEntity createTableEntity(final StorageAccount storageAccount, final String tableName,
                                         final String partitionKey, final String rowKey,
                                         final Map<String, Property> properties)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<TableEntity>() {
            @Override
            public TableEntity run() throws AzureCmdException {
                return sdkManager.createTableEntity(storageAccount, tableName, partitionKey, rowKey, properties);
            }
        });
    }


    @Override
    public TableEntity updateTableEntity(final StorageAccount storageAccount,
                                         final TableEntity tableEntity)
            throws AzureCmdException {
        return runWithRetry(storageAccount.getSubscriptionId(), new Func0<TableEntity>() {
            @Override
            public TableEntity run() throws AzureCmdException {
                return sdkManager.updateTableEntity(storageAccount, tableEntity);
            }
        });
    }

    @Override
    public void deleteTableEntity(final StorageAccount storageAccount,
                                  final TableEntity tableEntity)
            throws AzureCmdException {
        runWithRetry(storageAccount.getSubscriptionId(), new Func0<Void>() {
            @Override
            public Void run() throws AzureCmdException {
                sdkManager.deleteTableEntity(storageAccount, tableEntity);
                return null;
            }
        });
    }
}
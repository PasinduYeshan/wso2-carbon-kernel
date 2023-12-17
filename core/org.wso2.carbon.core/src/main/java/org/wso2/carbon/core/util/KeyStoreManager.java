/*
*  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.core.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonException;
import org.wso2.carbon.base.api.ServerConfigurationService;
import org.wso2.carbon.core.RegistryResources;
import org.wso2.carbon.core.internal.CarbonCoreDataHolder;
import org.wso2.carbon.core.keystore.KeyStoreManagementException;
import org.wso2.carbon.core.keystore.dao.KeyStoreDAO;
import org.wso2.carbon.core.keystore.dao.impl.KeyStoreDAOImpl;
import org.wso2.carbon.core.keystore.model.KeyStoreModel;
import org.wso2.carbon.core.keystore.util.KeyStoreMgtUtil;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.io.*;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The purpose of this class is to centrally manage the key stores.
 * Load key stores only once.
 * Reloading them over and over result a in a performance penalty.
 */
public class KeyStoreManager {

    private KeyStore primaryKeyStore = null;
    private KeyStore registryKeyStore = null;
    private KeyStore internalKeyStore = null;
    private static final ConcurrentHashMap<String, KeyStoreManager> mtKeyStoreManagers = new ConcurrentHashMap<>();
    private static final Log LOG = LogFactory.getLog(KeyStoreManager.class);

    private KeyStoreDAO keyStoreDAO = null;

    private final ConcurrentHashMap<String, KeyStoreBean> loadedKeyStores;
    private final int tenantId;
    private String tenantUUID = null;

    private final ServerConfigurationService serverConfigService;

    /**
     * Private Constructor of the KeyStoreManager
     *
     * @param tenantId
     * @param serverConfigService
     * @param registryService
     */
    private KeyStoreManager(int tenantId, ServerConfigurationService serverConfigService,
                            RegistryService registryService) {
        this.serverConfigService = serverConfigService;
        loadedKeyStores = new ConcurrentHashMap<>();
        this.tenantId = tenantId;
        try {
            this.tenantUUID = KeyStoreMgtUtil.getTenantUUID(tenantId);
        } catch (KeyStoreManagementException | UserStoreException e) {
            LOG.error("Error while getting the tenant UUID for tenant ID : " + tenantId, e);
        }

        keyStoreDAO = new KeyStoreDAOImpl();
    }

    public ServerConfigurationService getServerConfigService() {
        return serverConfigService;
    }

    /**
     * Get a KeyStoreManager instance for that tenant. This method will return an KeyStoreManager
     * instance if exists, or creates a new one. Only use this at runtime, or else,
     * use KeyStoreManager#getInstance(UserRegistry, ServerConfigurationService).
     *
     * @param tenantId id of the corresponding tenant
     * @return KeyStoreManager instance for that tenant
     */
    public static KeyStoreManager getInstance(int tenantId) {
        return getInstance(tenantId, CarbonCoreDataHolder.getInstance().
                getServerConfigurationService(), CryptoUtil.lookupRegistryService());
    }

    public static KeyStoreManager getInstance(int tenantId,
                                              ServerConfigurationService serverConfigService,
                                              RegistryService registryService) {
        CarbonUtils.checkSecurity();
        String tenantIdStr = Integer.toString(tenantId);
        if (!mtKeyStoreManagers.containsKey(tenantIdStr)) {
            mtKeyStoreManagers.put(tenantIdStr, new KeyStoreManager(tenantId,
                    serverConfigService, registryService));
        }
        return mtKeyStoreManagers.get(tenantIdStr);
    }

    /**
     * Get the key store object for the given key store name
     *
     * @param keyStoreName key store name
     * @return KeyStore object
     * @throws Exception If there is not a key store with the given name
     */
    public KeyStore getKeyStore(String keyStoreName) throws Exception {

        if (KeyStoreUtil.isPrimaryStore(keyStoreName)) {
            return getPrimaryKeyStore();
        }

        if (isCachedKeyStoreValid(keyStoreName)) {
            return loadedKeyStores.get(keyStoreName).getKeyStore();
        }

        Optional<KeyStoreModel> optionalKeyStoreModel = keyStoreDAO.getKeyStore(this.tenantUUID, keyStoreName);
        if (optionalKeyStoreModel.isPresent()) {
            KeyStoreModel keyStoreModel = optionalKeyStoreModel.get();

            byte[] bytes = keyStoreModel.getContent();
            KeyStore keyStore = KeyStore.getInstance(keyStoreModel.getType());
            CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
            char[] encryptedPassword = keyStoreModel.getPassword();
            char[] password = new String(cryptoUtil.base64DecodeAndDecrypt(Arrays.toString(encryptedPassword)))
                    .toCharArray();
            ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
            keyStore.load(stream, password);

            KeyStoreBean keyStoreBean = new KeyStoreBean(keyStore, keyStoreModel.getLastUpdated());

            if (loadedKeyStores.containsKey(keyStoreName)) {
                loadedKeyStores.replace(keyStoreName, keyStoreBean);
            } else {
                loadedKeyStores.put(keyStoreName, keyStoreBean);
            }
            return keyStore;
        } else {
            throw new SecurityException("Key Store with a name : " + keyStoreName + " does not exist.");
        }
    }

    /**
     * This method loads the private key of a given key store
     *
     * @param keyStoreName name of the key store
     * @param alias        alias of the private key
     * @return private key corresponding to the alias
     */
    public Key getPrivateKey(String keyStoreName, String alias) {
        try {
            if (KeyStoreUtil.isPrimaryStore(keyStoreName)) {
                return getDefaultPrivateKey();
            }

            KeyStoreModel keyStoreModel;
            KeyStore keyStore;

            Optional<KeyStoreModel> optionalKeyStoreModel = keyStoreDAO.getKeyStore(this.tenantUUID, keyStoreName);
            if (optionalKeyStoreModel.isPresent()) {
                keyStoreModel = optionalKeyStoreModel.get();
            } else {
                throw new SecurityException("Given Key store is not available in Database : " + keyStoreName);
            }

            CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
            char[] encryptedPassword = keyStoreModel.getPrivateKeyPass();
            char[] privateKeyPasswd = new String(cryptoUtil.base64DecodeAndDecrypt(Arrays.toString(encryptedPassword)))
                    .toCharArray();

            if (isCachedKeyStoreValid(keyStoreName)) {
                keyStore = loadedKeyStores.get(keyStoreName).getKeyStore();
                return keyStore.getKey(alias, privateKeyPasswd);
            }
            byte[] bytes = keyStoreModel.getContent();
            char[] keyStorePassword = new String(cryptoUtil.base64DecodeAndDecrypt(
                    Arrays.toString(keyStoreModel.getPassword()))).toCharArray();
            keyStore = KeyStore.getInstance(keyStoreModel.getType());
            ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
            keyStore.load(stream, keyStorePassword);

            KeyStoreBean keyStoreBean = new KeyStoreBean(keyStore, keyStoreModel.getLastUpdated());
            updateKeyStoreCache(keyStoreName, keyStoreBean);
            return keyStore.getKey(alias, privateKeyPasswd);
        } catch (Exception e) {
            LOG.error("Error loading the private key from the key store : " + keyStoreName);
            throw new SecurityException("Error loading the private key from the key store : " +
                    keyStoreName, e);
        }
    }

    /**
     * Get the key store password for the given key store name.
     * Note:  Caching has been not implemented for this method
     *
     * @param keyStoreName key store name
     * @return KeyStore object
     * @throws Exception If there is not a key store with the given name
     */
    public String getKeyStorePassword(String keyStoreName) throws KeyStoreManagementException, CryptoException {

        Optional<KeyStoreModel> optionalKeyStoreModel = keyStoreDAO.getKeyStore(this.tenantUUID, keyStoreName);
        if (optionalKeyStoreModel.isPresent()) {
            KeyStoreModel keyStoreModel = optionalKeyStoreModel.get();
            CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
            char[] encryptedPassword = keyStoreModel.getPassword();
            if(encryptedPassword != null){
                return new String(cryptoUtil.base64DecodeAndDecrypt(Arrays.toString(encryptedPassword)));
            } else {
                throw new SecurityException("Key Store Password of " + keyStoreName + " does not exist.");                
            }
        } else {
            throw new SecurityException("Key Store with a name : " + keyStoreName + " does not exist.");
        }
    }

    /**
     * Update the key store with the given name using the modified key store object provided.
     *
     * @param name     key store name
     * @param keyStore modified key store object
     * @throws Exception Registry exception or Security Exception
     */
    public void updateKeyStore(String name, KeyStore keyStore) throws Exception {
        ServerConfigurationService config = this.getServerConfigService();

        if (KeyStoreUtil.isPrimaryStore(name)) {
            String file = new File(
                    config
                            .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_FILE))
                    .getAbsolutePath();
            try (FileOutputStream out = new FileOutputStream(file)) {
                String password = config
                        .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_PASSWORD);
                keyStore.store(out, password.toCharArray());
            }
        }

        Optional<KeyStoreModel> optionalKeyStoreModel = keyStoreDAO.getKeyStore(this.tenantUUID, name);
        if (optionalKeyStoreModel.isPresent()) {
            KeyStoreModel keyStoreModel = optionalKeyStoreModel.get();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
            char[] encryptedPassword = keyStoreModel.getPassword();
            char[] password = new String(cryptoUtil.base64DecodeAndDecrypt(Arrays.toString(encryptedPassword)))
                    .toCharArray();
            keyStore.store(outputStream, password);
            outputStream.flush();
            outputStream.close();

            KeyStoreModel updatedKeyStoreModel = new KeyStoreModel.KeyStoreModelBuilder()
                    .id(keyStoreModel.getId())
                    .fileName(keyStoreModel.getFileName())
                    .type(keyStoreModel.getType())
                    .provider(keyStoreModel.getProvider())
                    .password(keyStoreModel.getPassword())
                    .privateKeyAlias(keyStoreModel.getPrivateKeyAlias())
                    .privateKeyPass(keyStoreModel.getPrivateKeyPass())
                    .content(outputStream.toByteArray())
                    .build();

            keyStoreDAO.updateKeyStore(this.tenantUUID, updatedKeyStoreModel);

            // TODO: is it correct to use new Date() here? The value stored in DB might be different.
            updateKeyStoreCache(name, new KeyStoreBean(keyStore, new Date()));
        } else {
            throw new SecurityException("Key Store with a name : " + name + " does not exist.");
        }
    }

    /**
     * Load the primary key store, this is allowed only for the super tenant
     *
     * @return primary key store object
     * @throws Exception Carbon Exception when trying to call this method from a tenant other
     *                   than tenant 0
     */
    public KeyStore getPrimaryKeyStore() throws Exception {
        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            if (primaryKeyStore == null) {

                ServerConfigurationService config = this.getServerConfigService();
                String file =
                        new File(config
                                .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_FILE))
                                .getAbsolutePath();
                KeyStore store = KeyStore
                        .getInstance(config
                                .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_TYPE));
                String password = config
                        .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_PASSWORD);
                FileInputStream in = null;
                try {
                    in = new FileInputStream(file);
                    store.load(in, password.toCharArray());
                    primaryKeyStore = store;
                } finally {
                    if (in != null) {
                        in.close();
                    }
                }
            }
            return primaryKeyStore;
        } else {
            throw new CarbonException("Permission denied for accessing primary key store. The primary key store is " +
                    "available only for the super tenant.");
        }
    }

    /**
     * Load the internal key store, this is allowed only for the super tenant
     *
     * @return internal key store object
     * @throws Exception Carbon Exception when trying to call this method from a tenant other
     *                   than tenant 0
     */
    public KeyStore getInternalKeyStore() throws Exception {

        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            if (internalKeyStore == null) {
                ServerConfigurationService config = this.getServerConfigService();
                if (config.
                        getFirstProperty(RegistryResources.SecurityManagement.SERVER_INTERNAL_KEYSTORE_FILE) == null) {
                    return null;
                }
                String file = new File(config
                        .getFirstProperty(RegistryResources.SecurityManagement.SERVER_INTERNAL_KEYSTORE_FILE))
                        .getAbsolutePath();
                KeyStore store = KeyStore.getInstance(config
                        .getFirstProperty(RegistryResources.SecurityManagement.SERVER_INTERNAL_KEYSTORE_TYPE));
                String password = config
                        .getFirstProperty(RegistryResources.SecurityManagement.SERVER_INTERNAL_KEYSTORE_PASSWORD);
                try (FileInputStream in = new FileInputStream(file)) {
                    store.load(in, password.toCharArray());
                    internalKeyStore = store;
                }
            }
            return internalKeyStore;
        } else {
            throw new CarbonException("Permission denied for accessing internal key store. The internal key store is " +
                    "available only for the super tenant.");
        }
    }
    
    /**
     * Load the register key store, this is allowed only for the super tenant
     *
     * @deprecated use {@link #getPrimaryKeyStore()} instead.
     *
     * @return register key store object
     * @throws Exception Carbon Exception when trying to call this method from a tenant other
     *                   than tenant 0
     */
    @Deprecated
    public KeyStore getRegistryKeyStore() throws Exception {
        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            if (registryKeyStore == null) {

                ServerConfigurationService config = this.getServerConfigService();
                String file =
                        new File(config
                                .getFirstProperty(RegistryResources.SecurityManagement.SERVER_REGISTRY_KEYSTORE_FILE))
                                .getAbsolutePath();
                KeyStore store = KeyStore
                        .getInstance(config
                                .getFirstProperty(RegistryResources.SecurityManagement.SERVER_REGISTRY_KEYSTORE_TYPE));
                String password = config
                        .getFirstProperty(RegistryResources.SecurityManagement.SERVER_REGISTRY_KEYSTORE_PASSWORD);
                FileInputStream in = null;
                try {
                    in = new FileInputStream(file);
                    store.load(in, password.toCharArray());
                    registryKeyStore = store;
                } finally {
                    if (in != null) {
                        in.close();
                    }
                }
            }
            return registryKeyStore;
        } else {
            throw new CarbonException("Permission denied for accessing registry key store. The registry key store is" +
                    " available only for the super tenant.");
        }
    }

    /**
     * Get the default private key, only allowed for tenant 0
     *
     * @return Private key
     * @throws Exception Carbon Exception for tenants other than tenant 0
     */
    public PrivateKey getDefaultPrivateKey() throws Exception {
        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            ServerConfigurationService config = this.getServerConfigService();
            String password = config
                    .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_PASSWORD);
            String alias = config
                    .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_KEY_ALIAS);
            return (PrivateKey) primaryKeyStore.getKey(alias, password.toCharArray());
        }
        throw new CarbonException("Permission denied for accessing primary key store. The primary key store is " +
                "available only for the super tenant.");
    }

    /**
     * Get default pub. key
     *
     * @return Public Key
     * @throws Exception Exception Carbon Exception for tenants other than tenant 0
     */
    public PublicKey getDefaultPublicKey() throws Exception {
        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            ServerConfigurationService config = this.getServerConfigService();
            String alias = config
                    .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_KEY_ALIAS);
            return (PublicKey) primaryKeyStore.getCertificate(alias).getPublicKey();
        }
        throw new CarbonException("Permission denied for accessing primary key store. The primary key store is " +
                "available only for the super tenant.");
    }

    /**
     * Get the private key password
     *
     * @return private key password
     * @throws CarbonException Exception Carbon Exception for tenants other than tenant 0
     */
    public String getPrimaryPrivateKeyPasssword() throws CarbonException {
        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            ServerConfigurationService config = this.getServerConfigService();
            return config
                    .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_PASSWORD);
        }
        throw new CarbonException("Permission denied for accessing primary key store. The primary key store is " +
                "available only for the super tenant.");
    }

    /**
     * This method is used to load the default public certificate of the primary key store
     *
     * @return Default public certificate
     * @throws Exception Permission denied for accessing primary key store
     */
    public X509Certificate getDefaultPrimaryCertificate() throws Exception {
        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            ServerConfigurationService config = this.getServerConfigService();
            String alias = config
                    .getFirstProperty(RegistryResources.SecurityManagement.SERVER_PRIMARY_KEYSTORE_KEY_ALIAS);
            return (X509Certificate) getPrimaryKeyStore().getCertificate(alias);
        }
        throw new CarbonException("Permission denied for accessing primary key store. The primary key store is " +
                "available only for the super tenant.");
    }

    private boolean isCachedKeyStoreValid(String keyStoreName) {
        boolean cachedKeyStoreValid = false;
        try {
            if (loadedKeyStores.containsKey(keyStoreName)) {
                Optional<KeyStoreModel> optionalKeyStoreModel = keyStoreDAO.getKeyStore(this.tenantUUID, keyStoreName);
                if (optionalKeyStoreModel.isPresent()) {
                    KeyStoreModel keyStoreModel = optionalKeyStoreModel.get();
                    KeyStoreBean keyStoreBean = loadedKeyStores.get(keyStoreName);
                    if (keyStoreBean.getLastModifiedDate().equals(keyStoreModel.getLastUpdated())) {
                        cachedKeyStoreValid = true;
                    }
                }
            }
        } catch (KeyStoreManagementException e) {
            String errorMsg = "Error reading key store meta data from Database.";
            LOG.error(errorMsg, e);
            throw new SecurityException(errorMsg, e);
        }
        return cachedKeyStoreValid;
    }

    private void updateKeyStoreCache(String keyStoreName, KeyStoreBean keyStoreBean) {
        if (loadedKeyStores.containsKey(keyStoreName)) {
            loadedKeyStores.replace(keyStoreName, keyStoreBean);
        } else {
            loadedKeyStores.put(keyStoreName, keyStoreBean);
        }
    }

    public KeyStore loadKeyStoreFromFileSystem(String keyStorePath, String password, String type) {
        CarbonUtils.checkSecurity();
        String absolutePath = new File(keyStorePath).getAbsolutePath();
        FileInputStream inputStream = null;
        try {
            KeyStore store = KeyStore.getInstance(type);
            inputStream = new FileInputStream(absolutePath);
            store.load(inputStream, password.toCharArray());
            return store;
        } catch (Exception e) {
            String errorMsg = "Error loading the key store from the given location.";
            LOG.error(errorMsg);
            throw new SecurityException(errorMsg, e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                LOG.warn("Error when closing the input stream.", e);
            }
        }
    }
}

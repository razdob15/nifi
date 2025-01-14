/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.vault.hashicorp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.nifi.vault.hashicorp.config.HashiCorpVaultConfiguration;
import org.apache.nifi.vault.hashicorp.config.HashiCorpVaultProperties;
import org.apache.nifi.vault.hashicorp.config.HashiCorpVaultPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.vault.authentication.SimpleSessionManager;
import org.springframework.vault.client.ClientHttpRequestFactoryFactory;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultTransitOperations;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.support.VaultResponseSupport;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend.KV_1;

/**
 * Implements the VaultCommunicationService using Spring Vault
 */
public class StandardHashiCorpVaultCommunicationService implements HashiCorpVaultCommunicationService {
    private final HashiCorpVaultConfiguration vaultConfiguration;
    private final VaultTemplate vaultTemplate;
    private final VaultTransitOperations transitOperations;
    private final Map<String, VaultKeyValueOperations> keyValueOperationsMap;

    /**
     * Creates a VaultCommunicationService that uses Spring Vault.
     * @param propertySources Property sources to configure the service
     * @throws HashiCorpVaultConfigurationException If the configuration was invalid
     */
    public StandardHashiCorpVaultCommunicationService(final PropertySource<?>... propertySources) throws HashiCorpVaultConfigurationException {
        vaultConfiguration = new HashiCorpVaultConfiguration(propertySources);

        vaultTemplate = new VaultTemplate(vaultConfiguration.vaultEndpoint(),
                ClientHttpRequestFactoryFactory.create(vaultConfiguration.clientOptions(), vaultConfiguration.sslConfiguration()),
                new SimpleSessionManager(vaultConfiguration.clientAuthentication()));

        transitOperations = vaultTemplate.opsForTransit();
        keyValueOperationsMap = new HashMap<>();
    }

    /**
     * Creates a VaultCommunicationService that uses Spring Vault.
     * @param vaultProperties Properties to configure the service
     * @throws HashiCorpVaultConfigurationException If the configuration was invalid
     */
    public StandardHashiCorpVaultCommunicationService(final HashiCorpVaultProperties vaultProperties) throws HashiCorpVaultConfigurationException {
        this(new HashiCorpVaultPropertySource(vaultProperties));
    }

    @Override
    public String encrypt(final String transitPath, final byte[] plainText) {
        return transitOperations.encrypt(transitPath, Plaintext.of(plainText)).getCiphertext();
    }

    @Override
    public byte[] decrypt(final String transitPath, final String cipherText) {
        return transitOperations.decrypt(transitPath, Ciphertext.of(cipherText)).getPlaintext();
    }

    /**
     * Writes the value to the "value" key of the secret with the path [keyValuePath]/[key].
     * @param keyValuePath The Vault path to use for the configured Key/Value v1 Secrets Engine
     * @param key The secret key
     * @param value The secret value
     */
    @Override
    public void writeKeyValueSecret(final String keyValuePath, final String key, final String value) {
        final VaultKeyValueOperations keyValueOperations = keyValueOperationsMap
                .computeIfAbsent(keyValuePath, path -> vaultTemplate.opsForKeyValue(path, KV_1));
        keyValueOperations.put(key, new SecretData(value));
    }

    /**
     * Returns the value of the "value" key from the secret at the path [keyValuePath]/[key].
     * @param keyValuePath The Vault path to use for the configured Key/Value v1 Secrets Engine
     * @param key The secret key
     * @return The value of the secret
     */
    @Override
    public Optional<String> readKeyValueSecret(final String keyValuePath, final String key) {
        final VaultKeyValueOperations keyValueOperations = keyValueOperationsMap
                .computeIfAbsent(keyValuePath, path -> vaultTemplate.opsForKeyValue(path, KV_1));
        final VaultResponseSupport<SecretData> response = keyValueOperations.get(key, SecretData.class);
        return response == null ? Optional.empty() : Optional.ofNullable(response.getRequiredData().getValue());
    }

    private static class SecretData {
        private final String value;

        @JsonCreator
        public SecretData(@JsonProperty("value") final String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}

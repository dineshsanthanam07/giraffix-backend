package com.giraffix;

import com.giraffix.concept.entity.Concept;
import com.giraffix.concept.port.ConceptAnalyzer;
import com.giraffix.concept.port.ConceptImageStorage;
import com.giraffix.concept.repository.ConceptRepository;
import com.giraffix.concept.service.ConceptAnalysisValidator;
import com.giraffix.concept.service.ConceptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConceptServiceTests {
    private ConceptAnalyzer analyzer;
    private ConceptImageStorage storage;
    private ConceptRepository repository;
    private PlatformTransactionManager transactions;
    private ConceptService service;
    private MockMultipartFile image;
    private final ConceptImageStorage.StoredImage stored = new ConceptImageStorage.StoredImage(
            "bucket", "concept/test/photo.png", "https://bucket.example/photo.png", "image/png", 100);

    @BeforeEach
    void setup() throws Exception {
        analyzer = mock(ConceptAnalyzer.class);
        storage = mock(ConceptImageStorage.class);
        repository = mock(ConceptRepository.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        when(analyzer.analyze(any(byte[].class), anyString())).thenReturn(ConceptFixtures.analysis());
        when(storage.upload(any(), any(byte[].class), anyString())).thenReturn(stored);
        when(repository.saveAndFlush(any(Concept.class))).thenAnswer(call -> call.getArgument(0));
        var mapper = JsonMapper.builder().build();
        service = new ConceptService(analyzer, storage, repository, mapper,
                new ConceptAnalysisValidator(mapper), transactions, 5242880, mock(com.giraffix.concept.service.ConceptLibrary.class));
        image = new MockMultipartFile("image", "reference.png", "image/png", ProductImageProcessorTests.fixture("png"));
    }

    @Test
    void invalidImageNeverCallsModelStorageOrDatabase() {
        assertThatThrownBy(() -> service.create("admin-subject", new MockMultipartFile("image", new byte[]{1})))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(analyzer, storage, repository, transactions);
    }

    @Test
    void modelFailureNeverUploadsOrSaves() {
        when(analyzer.analyze(any(byte[].class), anyString())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model unavailable"));
        assertThatThrownBy(() -> service.create("admin-subject", image)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(storage, repository, transactions);
    }

    @Test
    void uploadFailureNeverSaves() {
        when(storage.upload(any(), any(byte[].class), anyString())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upload unavailable"));
        assertThatThrownBy(() -> service.create("admin-subject", image)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository, transactions);
    }

    @Test
    void databaseFailureCleansUploadedImage() {
        when(repository.saveAndFlush(any(Concept.class))).thenThrow(new IllegalStateException("Database failed"));
        assertThatThrownBy(() -> service.create("admin-subject", image)).isInstanceOf(ResponseStatusException.class);
        verify(storage).delete(stored);
        verify(transactions).rollback(any());
    }

    @Test
    void commitFailureAlsoCleansUploadedImage() {
        doThrow(new IllegalStateException("Commit failed")).when(transactions).commit(any());
        assertThatThrownBy(() -> service.create("admin-subject", image)).isInstanceOf(ResponseStatusException.class);
        verify(storage).delete(stored);
    }

    @Test
    void cleanupFailureStillReturnsSaveFailure() {
        when(repository.saveAndFlush(any(Concept.class))).thenThrow(new IllegalStateException("Database failed"));
        doThrow(new IllegalStateException("Cleanup failed")).when(storage).delete(stored);
        assertThatThrownBy(() -> service.create("admin-subject", image))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getReason()).isEqualTo("Concept could not be saved"));
    }
}

package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.coyote.ActionCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Proves how {@link ProblemErrorReportValve} dispatches and how it fails safe, without a server:
 * a response the connector has already put in error is reported at once and never passed to the next
 * valve, any other request is passed on, and when the application beans cannot be had the response keeps
 * its status with no header and no body, never Tomcat's HTML page, and the valve throws nothing. The
 * body written with the beans present is proved against the running server by
 * {@code ConnectorErrorProblemIT}.
 */
class ProblemErrorReportValveTest {

    @Test
    void connectorRejectionIsNotPassedOnAndFailsSafeWithoutBeans() throws Exception {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        ProblemErrorReportValve valve = new ProblemErrorReportValve(
                beans.getBeanProvider(ProblemErrorController.class),
                beans.getBeanProvider(ProblemFactory.class));
        Valve next = mock(Valve.class);
        valve.setNext(next);
        Request request = mock(Request.class);
        Response response = rejectedResponse();

        assertThatNoException().isThrownBy(() -> valve.invoke(request, response));

        verify(next, never()).invoke(any(), any());
        verify(response).setSuspended(false);
        verify(response).setErrorReported();
        assertNothingWritten(response);
    }

    @Test
    void beanThatCannotBeCreatedFailsSafe() throws Exception {
        ObjectProvider<ProblemErrorController> failing = failingProvider();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        ProblemErrorReportValve valve = new ProblemErrorReportValve(failing,
                beans.getBeanProvider(ProblemFactory.class));
        valve.setNext(mock(Valve.class));
        Response response = rejectedResponse();

        assertThatNoException().isThrownBy(() -> valve.invoke(mock(Request.class), response));

        assertNothingWritten(response);
    }

    @Test
    void requestNotInErrorIsPassedOn() throws Exception {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        ProblemErrorReportValve valve = new ProblemErrorReportValve(
                beans.getBeanProvider(ProblemErrorController.class),
                beans.getBeanProvider(ProblemFactory.class));
        Valve next = mock(Valve.class);
        valve.setNext(next);
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(200);

        valve.invoke(request, response);

        verify(next).invoke(request, response);
        verify(response, never()).setErrorReported();
        assertNothingWritten(response);
    }

    @Test
    void asyncRequestInErrorIsLeftToTheContainer() throws Exception {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        ProblemErrorReportValve valve = new ProblemErrorReportValve(
                beans.getBeanProvider(ProblemErrorController.class),
                beans.getBeanProvider(ProblemFactory.class));
        Valve next = mock(Valve.class);
        valve.setNext(next);
        Request request = mock(Request.class);
        when(request.isAsync()).thenReturn(true);
        Response response = rejectedResponse();

        valve.invoke(request, response);

        verify(next).invoke(request, response);
        assertNothingWritten(response);
    }

    /**
     * Returns a response as the connector leaves a rejected request: in error with status 400, nothing
     * written, its error not yet reported, and I/O still allowed.
     *
     * @return the mocked response
     */
    private static Response rejectedResponse() {
        Response response = mock(Response.class);
        org.apache.coyote.Response coyoteResponse = mock(org.apache.coyote.Response.class);
        when(response.isError()).thenReturn(true);
        when(response.getStatus()).thenReturn(400);
        when(response.getContentWritten()).thenReturn(0L);
        when(response.setErrorReported()).thenReturn(true);
        when(response.getCoyoteResponse()).thenReturn(coyoteResponse);
        doAnswer(invocation -> {
            ((AtomicBoolean) invocation.getArgument(1)).set(true);
            return null;
        }).when(coyoteResponse).action(eq(ActionCode.IS_IO_ALLOWED), any());
        return response;
    }

    /**
     * Verifies that the valve changed neither the status nor a header and wrote no body.
     *
     * @param response the mocked response
     * @throws Exception never; declared for the verified stream and writer methods
     */
    private static void assertNothingWritten(Response response) throws Exception {
        verify(response, never()).setStatus(anyInt());
        verify(response, never()).setContentType(anyString());
        verify(response, never()).setHeader(anyString(), anyString());
        verify(response, never()).addHeader(anyString(), anyString());
        verify(response, never()).getOutputStream();
        verify(response, never()).getWriter();
        verify(response, never()).getReporter();
    }

    /**
     * Returns a provider whose bean cannot be created, as while the application context closes.
     *
     * @return the provider
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<ProblemErrorController> failingProvider() {
        ObjectProvider<ProblemErrorController> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenThrow(new BeanCreationException("problemErrorController"));
        return provider;
    }
}

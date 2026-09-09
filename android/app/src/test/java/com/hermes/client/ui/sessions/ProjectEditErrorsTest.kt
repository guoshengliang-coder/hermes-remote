package com.hermes.client.ui.sessions

import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.GatewayRpcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEditErrorsTest {
    @Test fun upstream_project_codes_map_to_registered_product_codes() {
        assertEquals(
            AppErrorCode.PROJECT_NOT_FOUND,
            projectEditError(GatewayRpcException(5062, "no such project"), "project_update").code,
        )
        assertEquals(
            AppErrorCode.PROJECT_NAME_INVALID,
            projectEditError(GatewayRpcException(5063, "name required"), "project_create").code,
        )
        assertEquals(
            AppErrorCode.PROJECT_SAVE_FAILED,
            projectEditError(GatewayRpcException(5061, "boom"), "project_delete").code,
        )
    }

    /** A transport failure carries no server code; it must not be read as "project is missing". */
    @Test fun a_transport_failure_falls_into_the_retryable_bucket() {
        val error = projectEditError(GatewayRpcException(0, "not connected"), "project_create")
        assertEquals(AppErrorCode.PROJECT_SAVE_FAILED, error.code)
        assertTrue(error.retryable)
        assertEquals("project_create", error.stage)
    }

    /** Retrying the identical call cannot fix a missing project or a rejected name. */
    @Test fun terminal_failures_are_not_offered_a_retry() {
        assertFalse(projectEditError(GatewayRpcException(5062, ""), "s").retryable)
        assertFalse(projectEditError(GatewayRpcException(5063, ""), "s").retryable)
    }
}

package com.github.karlsabo.pagerduty

import com.github.karlsabo.tools.lenientJson
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PagerDutyIncidentTest {

    @Test
    fun testPagerDutyIncidentDeserialization() {
        val jsonData = """
        {
            "incident_number": 123456,
            "title": "Service Response Alert",
            "description": "Service Response Alert",
            "created_at": "2025-07-21T21:00:26Z",
            "updated_at": "2025-07-21T21:24:50Z",
            "status": "resolved",
            "incident_key": null,
            "service": {
                "id": "ABC123",
                "type": "service_reference",
                "summary": "API Service",
                "self": "https://api.example.local/services/ABC123",
                "html_url": "https://example.pagerduty.com/service-directory/ABC123"
            },
            "assignments": [],
            "assigned_via": "escalation_policy",
            "last_status_change_at": "2025-07-21T21:24:50Z",
            "resolved_at": "2025-07-21T21:24:50Z",
            "first_trigger_log_entry": {
                "id": "LOG123456",
                "type": "trigger_log_entry_reference",
                "summary": "Triggered through the API.",
                "self": "https://api.example.local/log_entries/LOG123456",
                "html_url": "https://example.pagerduty.com/incidents/INC123456/log_entries/LOG123456"
            },
            "alert_counts": {
                "all": 1,
                "triggered": 0,
                "resolved": 1
            },
            "is_mergeable": true,
            "incident_type": {
                "name": "incident_default"
            },
            "escalation_policy": {
                "id": "ESC123",
                "type": "escalation_policy_reference",
                "summary": "Standard Escalation",
                "self": "https://api.example.local/escalation_policies/ESC123",
                "html_url": "https://example.pagerduty.com/escalation_policies/ESC123"
            },
            "teams": [
                {
                    "id": "TEAM123",
                    "type": "team_reference",
                    "summary": "Engineering Team",
                    "self": "https://api.example.local/teams/TEAM123",
                    "html_url": "https://example.pagerduty.com/teams/TEAM123"
                }
            ],
            "pending_actions": [],
            "acknowledgements": [],
            "basic_alert_grouping": null,
            "alert_grouping": null,
            "last_status_change_by": {
                "id": "USER123",
                "type": "user_reference",
                "summary": "John Smith",
                "self": "https://api.example.local/users/USER123",
                "html_url": "https://example.pagerduty.com/users/USER123"
            },
            "priority": null,
            "resolve_reason": null,
            "incidents_responders": [],
            "responder_requests": [],
            "subscriber_requests": [],
            "urgency": "high",
            "id": "INC123456",
            "type": "incident",
            "summary": "[#123456] Service Response Alert",
            "self": "https://api.example.local/incidents/INC123456",
            "html_url": "https://example.pagerduty.com/incidents/INC123456"
        }
        """

        val incident = lenientJson.decodeFromString<PagerDutyIncident>(jsonData)

        assertEquals("INC123456", incident.id)
        assertEquals(123456, incident.incidentNumber)
        assertNull(incident.incidentKey)
        assertEquals("Service Response Alert", incident.title)
        assertEquals("Service Response Alert", incident.description)
        assertEquals("resolved", incident.status)
        assertEquals("high", incident.urgency)
        assertEquals(Instant.parse("2025-07-21T21:00:26Z"), incident.createdAt)
        assertEquals(Instant.parse("2025-07-21T21:24:50Z"), incident.updatedAt)
        assertEquals(Instant.parse("2025-07-21T21:24:50Z"), incident.lastStatusChangeAt)
        assertEquals(Instant.parse("2025-07-21T21:24:50Z"), incident.resolvedAt)
        assertEquals("https://example.pagerduty.com/incidents/INC123456", incident.htmlUrl)
        assertEquals("[#123456] Service Response Alert", incident.summary)

        assertNotNull(incident.service)
        assertEquals("ABC123", incident.service?.id)
        assertEquals("service_reference", incident.service?.type)
        assertEquals("API Service", incident.service?.summary)
        assertEquals("https://api.example.local/services/ABC123", incident.service?.selfUrl)
        assertEquals("https://example.pagerduty.com/service-directory/ABC123", incident.service?.htmlUrl)
    }
}

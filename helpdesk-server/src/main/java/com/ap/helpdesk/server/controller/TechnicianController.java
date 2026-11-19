package com.ap.helpdesk.server.controller;

import java.util.List;

import com.ap.helpdesk.server.account.TechnicianRepository;
import com.ap.helpdesk.server.account.TechnicianRepository.TechnicianSummary;
import com.ap.helpdesk.server.account.UserAccount.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Handles technician directory and assignment views. */
@RestController
@RequestMapping("/api/technicians")
public class TechnicianController {
    private final TechnicianRepository technicians;

    public TechnicianController(TechnicianRepository technicians) {
        this.technicians = technicians;
    }

    /** Lists technicians available in the administrator's assignment form. */
    @GetMapping
    public List<TechnicianSummary> listTechnicians() {
        return technicians.findDirectory(Role.TECHNICIAN);
    }

    /** Lists tickets currently assigned to the given technician. */
    @GetMapping("/{id}/tickets")
    public void listAssignedTickets(@PathVariable("id") long id) {
        // TODO: Allow the assigned technician or an administrator to view these tickets.
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Assigned ticket listing is pending");
    }
}

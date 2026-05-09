package com.demo.upimesh.controller;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.MeshSimulatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class DashboardController {

    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionRepository txRepo;
    @Autowired private MeshSimulatorService meshSimulator;

    @GetMapping("/")
    public String home(Model model) {
        List<Account> accounts = accountRepo.findAll();
        List<Transaction> transactions = txRepo.findAll();
        model.addAttribute("accounts", accounts);
        model.addAttribute("transactions", transactions);
        model.addAttribute("nodeCount", meshSimulator.getNodeCount());
        model.addAttribute("packetCount", meshSimulator.getPacketCount());
        return "dashboard";
    }
}

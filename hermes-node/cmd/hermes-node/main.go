package main

import (
	"fmt"
	"log"
	"os"

	"github.com/yecos/Hermes-Os/hermes-node/internal/config"
	"github.com/yecos/Hermes-Os/hermes-node/internal/core"
	"github.com/yecos/Hermes-Os/hermes-node/internal/httpapi"
	"github.com/yecos/Hermes-Os/hermes-node/internal/mcp"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	node := core.New(cfg)
	mode := "serve"
	if len(os.Args) > 1 {
		mode = os.Args[1]
	}
	switch mode {
	case "serve":
		if cfg.TokenGenerated {
			log.Printf("HERMES_NODE_TOKEN was not set. Generated temporary token: %s", cfg.Token)
		}
		if err := httpapi.New(node).ListenAndServe(); err != nil {
			log.Fatal(err)
		}
	case "mcp":
		if err := mcp.New(node).Serve(); err != nil {
			log.Fatal(err)
		}
	case "status":
		fmt.Println(core.JSON(node.Device()))
	default:
		fmt.Fprintf(os.Stderr, "usage: hermes-node [serve|mcp|status]\n")
		os.Exit(2)
	}
}

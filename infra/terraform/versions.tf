terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state — bootstrap the bucket + lock table once (see README "Remote state"),
  # then uncomment and run `terraform init -migrate-state`.
  # backend "s3" {
  #   bucket         = "durdans-lims-tfstate"
  #   key            = "lims/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "durdans-lims-tflock"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

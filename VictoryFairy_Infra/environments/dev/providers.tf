provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "VictoryFairy"
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}

# AWS Deployment Guide

This guide covers deploying Blackthorn Sterling to AWS using CloudFormation.

## Prerequisites

- AWS account with appropriate permissions
- AWS CLI installed and configured
- SSH key pair created in the target region
- Existing VPC and subnet (or use default VPC)

## Quick Start

### 1. Deploy with CloudFormation

```bash
# Set your AWS region
export AWS_REGION=us-east-1

# Deploy the stack
aws cloudformation create-stack \
  --stack-name blackthorn-sterling \
  --template-body file://infra/aws/cloudformation.yaml \
  --parameters \
    ParameterKey=InstanceType,ParameterValue=t3.large \
    ParameterKey=KeyName,ParameterValue=your-key-name \
    ParameterKey=VpcId,ParameterValue=vpc-xxxxxxxx \
    ParameterKey=SubnetId,ParameterValue=subnet-xxxxxxxx \
    ParameterKey=BackupBucketName,ParameterValue=blackthorn-sterling-backups \
  --capabilities CAPABILITY_IAM \
  --region $AWS_REGION
```

### 2. Monitor stack creation

```bash
aws cloudformation describe-stacks \
  --stack-name blackthorn-sterling \
  --region $AWS_REGION
```

### 3. Get instance details

```bash
aws cloudformation describe-stacks \
  --stack-name blackthorn-sterling \
  --query 'Stacks[0].Outputs' \
  --region $AWS_REGION
```

### 4. SSH into the instance

```bash
ssh -i /path/to/your-key.pem ubuntu@<PublicIP>
```

### 5. Configure the engine

```bash
sudo nano /opt/blackthorn-sterling/config.yaml
sudo nano /opt/blackthorn-sterling/.env
```

Add your API keys:
```bash
ALPACA_KEY_ID=your_key_here
ALPACA_SECRET=your_secret_here
API_SECRET=your_api_secret_here
OPERATOR_PASSWORD=your_operator_password_here
```

### 6. Restart the service

```bash
sudo systemctl restart blackthorn-sterling
sudo systemctl status blackthorn-sterling
```

---

## Manual Deployment (EC2 Console)

### 1. Launch EC2 Instance

1. Go to EC2 Console → Launch Instance
2. Name: `blackthorn-sterling`
3. AMI: Ubuntu 22.04 LTS
4. Instance type: `t3.large` (2 vCPU, 8GB RAM)
5. Key pair: Select your SSH key
6. Network settings:
   - VPC: Select your VPC
   - Subnet: Select your subnet
   - Auto-assign public IP: Enable
   - Security group: Create new with rules:
     - SSH (22) from your IP
     - HTTP (8080) from your IP or 0.0.0.0/0
     - WebSocket (8081) from your IP or 0.0.0.0/0
7. IAM role: Create role with:
   - AmazonSSMManagedInstanceCore
   - S3 access for backup bucket
8. User data:
   ```bash
   #!/bin/bash
   apt-get update
   apt-get install -y docker.io docker-compose git
   systemctl enable docker
   systemctl start docker
   cd /opt
   git clone https://github.com/sivuhsGorha/options-pricing-engine.git
   cd options-pricing-engine
   ./infra/linux/deploy.sh
   ```
9. Launch

### 2. Configure IAM Role

Go to IAM Console → Roles → Create role:
- Trusted entity: EC2
- Permissions:
  - `AmazonSSMManagedInstanceCore`
  - Inline policy for S3 backup bucket access

### 3. Create S3 Backup Bucket

```bash
aws s3 mb s3://blackthorn-sterling-backups
aws s3api put-bucket-encryption \
  --bucket blackthorn-sterling-backups \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

### 4. Configure Instance for Backups

SSH into the instance and edit `.env`:
```bash
S3_BUCKET=blackthorn-sterling-backups
AWS_REGION=us-east-1
```

### 5. Set Up CloudWatch Metrics

Install CloudWatch agent:
```bash
wget https://s3.amazonaws.com/amazoncloudwatch-agent/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb
sudo dpkg -i amazon-cloudwatch-agent.deb
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config \
  -m ec2 \
  -s
```

---

## CloudWatch Monitoring

### Dashboard

The CloudFormation template creates a dashboard with:
- CPU utilization
- Memory utilization
- (Add more as needed)

Access at: `https://<region>.console.aws.amazon.com/cloudwatch/home#dashboards:name=Blackthorn%20Sterling`

### Alerts

The template creates a CPU alarm:
- Triggers when CPU > 80% for 5 minutes
- Sends to SNS (configure separately)

---

## Cost Optimization

### Use EC2 Spot Instances

Save 50-70% by using spot instances:

```yaml
# In CloudFormation template, modify EngineInstance:
SpotOptions:
  SpotInstanceType: one-time
  InstanceInterruptionBehavior: terminate
```

### Right-Sizing

- **t3.medium** (2 vCPU, 4GB): $25/month
- **t3.large** (2 vCPU, 8GB): $50/month (recommended)
- **t3.xlarge** (4 vCPU, 16GB): $100/month

### Reserved Instances

If running 24/7, purchase reserved instances:
- **1-year term**: ~30% savings
- **3-year term**: ~50% savings

---

## Security Best Practices

### 1. Use Security Groups

- Restrict SSH to your IP only
- Use VPC with private subnets if possible
- Don't expose ports unnecessarily

### 2. IAM Roles

- Never hardcode AWS credentials
- Use instance profiles with minimal permissions
- Rotate credentials regularly

### 3. Encryption

- Enable EBS encryption
- Use S3 server-side encryption
- Encrypt data at rest and in transit

### 4. Network

- Use VPC endpoints for S3 (no internet gateway needed)
- Enable VPC flow logs for monitoring
- Use NAT gateway only if needed

---

## Troubleshooting

### Instance won't start

```bash
# Check CloudFormation events
aws cloudformation describe-stack-events \
  --stack-name blackthorn-sterling

# Check EC2 console for instance status
# Check user data logs in /var/log/cloud-init-output.log
```

### Can't SSH

```bash
# Verify security group allows SSH from your IP
aws ec2 describe-security-groups \
  --group-ids <security-group-id>

# Check instance is running
aws ec2 describe-instance-status \
  --instance-ids <instance-id>
```

### Service not running

```bash
# SSH into instance
ssh -i key.pem ubuntu@<public-ip>

# Check service status
sudo systemctl status blackthorn-sterling

# View logs
sudo journalctl -u blackthorn-sterling -f
```

### Backup failures

```bash
# Check IAM role has S3 permissions
aws iam get-role-policy \
  --role-name <role-name> \
  --policy-name S3BackupAccess

# Check S3 bucket exists
aws s3 ls s3://blackthorn-sterling-backups
```

---

## Scaling Considerations

### Horizontal Scaling

If you need multiple instances:
1. Use Application Load Balancer
2. Deploy multiple instances across AZs
3. Use shared state (S3, DynamoDB, or RDS)

### Vertical Scaling

If you need more resources:
1. Stop instance
2. Change instance type
3. Start instance
4. Verify application still works

---

## Disaster Recovery

### Backups

- Daily backups to S3 (automated)
- EBS snapshots (manual or automated)
- Git repository (code only)

### Recovery

1. Launch new instance from AMI
2. Restore data from S3
3. Update DNS (if using custom domain)
4. Test all functionality

---

## Migration from VPS to AWS

If migrating from a VPS:

1. **Export data**:
   ```bash
   # On VPS
   tar -czf /tmp/blackthorn-backup.tar.gz /opt/blackthorn-sterling/data
   scp /tmp/blackthorn-backup.tar.gz user@aws-instance:/tmp/
   ```

2. **Import data**:
   ```bash
   # On AWS instance
   tar -xzf /tmp/blackthorn-backup.tar.gz -C /
   ```

3. **Update configuration**:
   - Update API keys in `.env`
   - Update S3 bucket name
   - Test all functionality

---

## Clean Up

To delete the AWS stack:

```bash
aws cloudformation delete-stack \
  --stack-name blackthorn-sterling \
  --region $AWS_REGION
```

Note: This does not delete the S3 bucket. Delete manually if needed:

```bash
aws s3 rb s3://blackthorn-sterling-backups --force
```
